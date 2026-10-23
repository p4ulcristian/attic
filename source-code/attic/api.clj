(ns attic.api
  "Files in an S3 bucket (R2, S3, any S3 API), named by their content and
   recorded in a table. attic's one public namespace.

     (def attic (attic/make-attic {:endpoint            \"https://<account>.r2.cloudflarestorage.com\"
                                   :bucket              \"shop-files\"
                                   :access-key          access-key
                                   :secret-key          secret-key        ; both safe's secrets
                                   :region              \"us-east-1\"     ; R2 checks none
                                   :public-url          nil               ; a private bucket
                                   :link-expiry-seconds 300
                                   :connect-timeout-ms  10000
                                   :request-timeout-ms  60000
                                   :retries             {:max-retries 4 :first-wait-ms 500 :max-wait-ms 4000}}
                                  {:log log :meter meter}))

     (attic/store-file! attic transaction pdf-bytes {:key-prefix            \"invoices\"
                                                     :allowed-content-types #{\"application/pdf\"}
                                                     :file-name             nil})
     ;=> #:file{:key \"invoices/3f1c….pdf\" :sha256 \"3f1c…\" :size 48213
     ;          :content-type \"application/pdf\" :stored-at #inst \"…\"}

     (attic/make-signed-link attic \"invoices/3f1c….pdf\")   ; reads it for :link-expiry-seconds

   A file's key is its content's: <prefix>/<the sha256's first 32 hex
   digits as a uuid>.<extension of its kind>. The same bytes are the same
   key, stored once; an object is never written over (the put says
   If-None-Match: *). Every file stored is a row of attic.files
   (files-table, a map for cellar), its full sha256 with it: a key whose
   row has another sha256 is refused, :attic/key-taken. The bytes go to
   the bucket before the row is written, so a row always has its object;
   an object whose row was rolled back stays, and the same bytes stored
   again find it.

   A file's kind is told by its bytes (attic.kinds), never by its name or
   what the browser says; each call says which kinds it takes.

   Content-named, one file may be named by many rows of the app (two
   orders with the same PDF). A row names a file through a reference to
   :file/key ({:references {:to :file/key :on-delete :refuse :on-update :refuse
   :checked-at :statement}}: a delete of a named file is refused);
   delete-file! deletes a file only
   when none does (Postgres's reference refuses the row's delete, which
   attic answers :still-named), its row and its object together. attic
   knows no owners: a file's prefix is the app's to choose, and
   delete-unnamed-files! deletes every file under one that no row names.

   attic writes to the app's log only, :log, and counts on the app's
   meter, :meter (attic.metrics)."
  (:require
   [attic.bucket :as bucket]
   [attic.kinds :as kinds]
   [attic.links :as links]
   [attic.metrics :as metrics]
   [attic.settings :as settings]
   [attic.upload :as upload]
   [cellar.api :as cellar]
   [clojure.string :as str]
   [logbook.api :as logbook])
  (:import
   (java.security MessageDigest)
   (java.time Instant)))

(defn- make-query [file-name]
  (cellar/make-query-from-sql-file (str "attic/" file-name)))

(def ^:private insert-file (make-query "insert_file.sql"))
(def ^:private find-file-row (make-query "find_file.sql"))
(def ^:private list-file-rows (make-query "list_files.sql"))
(def ^:private delete-file-row (make-query "delete_file.sql"))

;; -----------------------------------------------------------------------------
;; Settings and the table

(def settings-shape
  "make-attic's settings, every key required. See attic.settings/settings-shape."
  settings/settings-shape)

(def upload-settings-shape
  "make-upload-handler's settings, every key required. See
   attic.settings/upload-settings-shape."
  settings/upload-settings-shape)

(def known-content-types
  "Every content type attic tells by its bytes: what :allowed-content-types
   may hold."
  kinds/known-content-types)

(def files-table
  "Every file stored: attic.files, a map for cellar, to pass with the app's
   tables. A row is never changed; delete-file! deletes it with its object,
   when no row of the app names it (a reference to :file/key)."
  {:table     :attic/files
   :columns   [[:file/key          :string       {:primary-key? true}]
               [:file/sha256       :string]
               [:file/size         :int]
               [:file/content-type :string]
               [:file/stored-at    :time/instant {:default :now}]]
   :indexes   []
   :uniques   []
   :checks    []
   :references []
   :triggers   []
   :gates     nil
   :on-change nil})

;; -----------------------------------------------------------------------------
;; The attic

(defn make-attic
  "An attic from its settings and options ({:log log :meter meter}), every
   key required, checked first (:attic/bad-settings, :attic/bad-options);
   declares attic's metrics on the meter. Returns the attic, for every
   other function; stop it with stop-attic!."
  [attic-settings options]
  (settings/check-data-for-shape "The settings" :attic/bad-settings settings/settings-shape attic-settings)
  (settings/check-data-for-shape "The options" :attic/bad-options settings/options-shape options)
  (let [{:keys [log meter]} options]
    ;; a log logbook didn't make is refused now, not at the first failure
    (logbook/with-context log {})
    (metrics/declare-metrics! meter)
    {:settings attic-settings
     :client   (bucket/make-client attic-settings)
     :log      log
     :meter    meter}))

(defn stop-attic!
  "Stops the attic's client."
  [attic]
  (bucket/stop-client! (:client attic)))

;; -----------------------------------------------------------------------------
;; Storing

(defn- hash-bytes [^bytes bs]
  (str/join (map #(format "%02x" (bit-and % 0xff)) (.digest (MessageDigest/getInstance "SHA-256") bs))))

(defn- make-key
  "<prefix>/<the sha256's first 32 hex digits as a uuid>.<extension>"
  [key-prefix sha256 content-type]
  (let [h (subs sha256 0 32)]
    (str key-prefix "/" (subs h 0 8) "-" (subs h 8 12) "-" (subs h 12 16) "-" (subs h 16 20) "-" (subs h 20 32)
         "." (get kinds/extensions-of-content-types content-type))))

(defn- remove-added [row] (dissoc row :added))

(defn- put-and-record!
  "Puts the bytes unless their key is recorded, then records them; the
   row. A key recorded with another sha256 throws :attic/key-taken."
  [{:keys [settings client meter]} pool-or-transaction ^bytes bs key-prefix content-type]
  (let [sha256     (hash-bytes bs)
        object-key (make-key key-prefix sha256 content-type)
        recorded   (cellar/find-one-row pool-or-transaction find-file-row {:file/key object-key})
        check      (fn [row]
                     (when (not= sha256 (:file/sha256 row))
                       (throw (ex-info (str "The key " object-key " is recorded with other bytes")
                                       {:type :attic/key-taken :object-key object-key})))
                     row)]
    (if recorded
      (do (metrics/count-stored! meter :already-stored)
          (check recorded))
      (let [put (try (bucket/put-object-once! client (:bucket settings) object-key bs content-type)
                     (catch Exception e
                       (metrics/count-bucket-failure! meter :store)
                       (throw e)))
            row (cellar/find-one-row pool-or-transaction insert-file
                                     {:file/key object-key :file/sha256 sha256 :file/size (alength bs)
                                      :file/content-type content-type})]
        (metrics/count-stored! meter (if (= :put put) :stored :already-stored))
        (remove-added (check row))))))

(defn- refuse-wrong-kind [meter]
  (metrics/count-refused! meter :wrong-kind)
  :wrong-kind)

(defn- store-if-allowed!
  "The row, or :wrong-kind."
  [attic pool-or-transaction bs {:keys [key-prefix allowed-content-types file-name]}]
  (let [content-type (kinds/find-content-type bs file-name)]
    (if (contains? allowed-content-types content-type)
      (put-and-record! attic pool-or-transaction bs key-prefix content-type)
      (refuse-wrong-kind (:meter attic)))))

(defn store-file!
  "Stores the bytes, named by their content, and records them, on a pool
   or in a transaction (then the row exists only if that commits; the
   object stays either way). Options, every key required, checked first:
   :key-prefix, the folder the key starts with (\"invoices\");
   :allowed-content-types, the kinds the bytes may be, a set of
   known-content-types; :file-name, the file's name or nil (only a .gltf
   needs it). Returns the file's row, #:file{:key :sha256 :size
   :content-type :stored-at}, the same for the same bytes. Bytes of
   another kind throw :attic/wrong-kind, the bucket's failure
   :attic/bucket-failed, a key recorded with other bytes :attic/key-taken."
  [attic pool-or-transaction ^bytes bs options]
  (settings/check-data-for-shape "The options" :attic/bad-options settings/store-options-shape options)
  (let [row (store-if-allowed! attic pool-or-transaction bs options)]
    (when (= :wrong-kind row)
      (throw (ex-info (str "The bytes are none of " (pr-str (sort (:allowed-content-types options))))
                      {:type :attic/wrong-kind :found (kinds/find-content-type bs (:file-name options))})))
    row))

;; -----------------------------------------------------------------------------
;; Reading

(defn find-file
  "The file's row, or nil when no file has that key."
  [pool-or-transaction object-key]
  (cellar/find-one-row pool-or-transaction find-file-row {:file/key object-key}))

(defn list-files
  "The files' rows, by key. Options, every key required: :key-prefix
   (\"\" for all), :after-key (nil to start at the first; the last key of
   the page before), :limit."
  [pool-or-transaction options]
  (let [{:keys [key-prefix after-key limit]}
        (settings/check-data-for-shape "The options" :attic/bad-options settings/list-options-shape options)]
    (cellar/find-rows pool-or-transaction list-file-rows {:key-prefix key-prefix :after-key after-key :limit limit})))

(defn read-file-bytes
  "The file's bytes from the bucket, or nil when the bucket has no object
   at the key; the bucket's failure throws :attic/bucket-failed."
  [{:keys [settings client meter]} object-key]
  (try
    (bucket/read-object-bytes client (:bucket settings) object-key)
    (catch Exception e
      (metrics/count-bucket-failure! meter :read)
      (throw e))))

(defn- is-foreign-key-violation?
  "Whether Postgres refused because a row still points at the one deleted."
  [^Throwable e]
  (loop [t e]
    (cond (nil? t) false
          (and (instance? java.sql.SQLException t) (= "23503" (.getSQLState ^java.sql.SQLException t))) true
          :else (recur (.getCause t)))))

(defn- delete-row-and-object!
  "In one transaction: the row deleted (Postgres refuses while a row
   names it), then the object; kept only if both went."
  [{:keys [settings client meter]} pool object-key]
  (cellar/run-in-transaction! pool {:read-only? false}
                              (fn [transaction]
                                (when-let [row (cellar/find-one-row transaction delete-file-row {:file/key object-key})]
                                  (try (bucket/delete-object! client (:bucket settings) object-key)
                                       (catch Exception e
                                         (metrics/count-bucket-failure! meter :delete)
                                         (throw e)))
                                  row))))

(defn delete-file!
  "Deletes a file: its row and its object, on a pool, in a transaction of
   its own (an object deleted can't come back with a rollback, so a
   transaction handed in is refused, :attic/needs-a-pool: delete after the
   app's own rows are kept). Only when no row of the app names it through
   a reference to :file/key: then nothing is deleted. Returns :deleted,
   :still-named or :not-found; counted (attic_files_deleted_total), a
   deletion written to the log (:info :attic/file-deleted). The object is
   deleted before the row's delete is kept: a file stored again at once
   puts its bytes anew; a failed commit after it leaves a row without its
   object, which read-file-bytes answers nil. The bucket's failure throws
   :attic/bucket-failed, the row kept."
  [{:keys [log meter] :as attic} pool object-key]
  (when (cellar/is-transaction? pool)
    (throw (ex-info (str "delete-file! runs in a transaction of its own: hand it the pool, after the app's transaction is kept"
                         " (delete-file-after-commit! keeps it for then)")
                    {:type :attic/needs-a-pool :object-key object-key})))
  (let [outcome (try
                  (if-let [row (delete-row-and-object! attic pool object-key)]
                    (do (log :info :attic/file-deleted {:attic/key object-key :attic/size (:file/size row)})
                        :deleted)
                    :not-found)
                  (catch Exception e
                    (if (is-foreign-key-violation? e) :still-named (throw e))))]
    (metrics/count-deleted! meter outcome)
    outcome))

(defn delete-file-after-commit!
  "Keeps delete-file! on the pool to run once the app's transaction
   commits (cellar's run-after-commit!), so a handler deleting the rows
   that named a file deletes the file too, after they are kept; never
   when the transaction rolls back. Returns nil: the outcome comes later,
   counted and logged as delete-file!'s (:still-named when a row still
   names it), and a failure of the bucket is written to the pool's log by
   cellar (:cellar/after-commit-failed). Lost if the process dies between
   the commit and the delete: delete-unnamed-files! in a background job
   finds what is left. Handed a pool for the transaction, cellar refuses,
   :cellar/not-a-transaction.

     (cellar/run-statement! transaction delete-invoice {:invoice/id id})
     (attic/delete-file-after-commit! attic pool transaction invoice-key)"
  [attic pool transaction object-key]
  (cellar/run-after-commit! transaction #(delete-file! attic pool object-key)))

(defn delete-unnamed-files!
  "Deletes every file under a prefix that no row of the app names
   (delete-file! each, a page at a time): for a background job after the
   app deletes its rows. Options, every key required: :key-prefix (\"\"
   for all), :page-size. Returns {:deleted n :still-named n}."
  [attic pool options]
  (let [{:keys [key-prefix page-size]}
        (settings/check-data-for-shape "The options" :attic/bad-options settings/delete-unnamed-options-shape options)]
    (loop [after-key nil counts {:deleted 0 :still-named 0}]
      (let [rows (cellar/find-rows pool list-file-rows {:key-prefix key-prefix :after-key after-key :limit page-size})
            counts (reduce (fn [counts {:file/keys [key]}]
                             (let [outcome (delete-file! attic pool key)]
                               (if (contains? counts outcome) (update counts outcome inc) counts)))
                           counts rows)]
        (if (< (count rows) page-size)
          counts
          (recur (:file/key (peek rows)) counts))))))

(defn make-signed-link
  "A link that reads the file for the settings' :link-expiry-seconds,
   from a private bucket too (SigV4's query-string signature). Whoever
   holds it reads that file until then: hand it only to whom the app lets
   see the file."
  [{:keys [settings]} object-key]
  (links/make-signed-link settings object-key (Instant/now)))

(defn make-public-link
  "The file's address under the settings' :public-url; throws
   :attic/no-public-url when it is nil."
  [{:keys [settings]} object-key]
  (links/make-public-link settings object-key))

;; -----------------------------------------------------------------------------
;; Uploads

(defn make-upload-handler
  "A Ring handler for an upload through the server: a multipart POST, one
   part per file (attic.upload says the answer). The settings
   (upload-settings-shape: :key-prefix :allowed-content-types
   :max-file-bytes :max-files) and the options (:pool, :wire, the app's,
   and :may-upload?, (fn [request] true-or-false), asked before the body
   is read), every key required, are checked first. The app puts it at
   its own path, behind its own checks."
  [attic upload-settings options]
  (settings/check-data-for-shape "The settings" :attic/bad-settings settings/upload-settings-shape upload-settings)
  (settings/check-data-for-shape "The options" :attic/bad-options settings/upload-options-shape options)
  (let [{:keys [pool wire may-upload?]} options
        {:keys [key-prefix allowed-content-types]} upload-settings]
    (upload/make-upload-handler
     (assoc upload-settings
            :wire                wire
            :may-upload?         may-upload?
            :log                 (:log attic)
            :count-refused!      #(metrics/count-refused! (:meter attic) %)
            :run-in-transaction! #(cellar/run-in-transaction! pool {:read-only? false} %)
            :store-part!         (fn [transaction {:keys [file-name bytes]}]
                                   (store-if-allowed! attic transaction bytes {:key-prefix            key-prefix
                                                                               :allowed-content-types allowed-content-types
                                                                               :file-name             file-name}))))))

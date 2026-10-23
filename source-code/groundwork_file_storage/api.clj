(ns groundwork-file-storage.api
  "Files in an S3 bucket (R2, S3, any S3 API), named by their content and
   recorded in a table. groundwork-file-storage's one public namespace.

     (def file-storage (file-storage/make-file-storage {:endpoint            \"https://<account>.r2.cloudflarestorage.com\"
                                   :bucket              \"shop-files\"
                                   :access-key          access-key
                                   :secret-key          secret-key        ; both groundwork-settings's secrets
                                   :region              \"us-east-1\"     ; R2 checks none
                                   :public-url          nil               ; a private bucket
                                   :link-expiry-seconds 300
                                   :connect-timeout-ms  10000
                                   :request-timeout-ms  60000
                                   :retries             {:max-retries 4 :first-wait-ms 500 :max-wait-ms 4000}}
                                  {:log log :meter meter}))

     (file-storage/store-file! file-storage transaction pdf-bytes {:key-prefix            \"invoices\"
                                                     :allowed-content-types #{\"application/pdf\"}
                                                     :file-name             nil})
     ;=> #:file{:key \"invoices/3f1c….pdf\" :sha256 \"3f1c…\" :size 48213
     ;          :content-type \"application/pdf\" :stored-at #inst \"…\"}

     (file-storage/make-signed-link file-storage \"invoices/3f1c….pdf\")   ; reads it for :link-expiry-seconds

   A file's key is its content's: <prefix>/<the sha256's first 32 hex
   digits as a uuid>.<extension of its kind>. The same bytes are the same
   key, stored once; an object is never written over (the put says
   If-None-Match: *). Every file stored is a row of groundwork_file_storage.files
   (files-table, a map for groundwork-postgres), its full sha256 with it: a key whose
   row has another sha256 is refused, :groundwork-file-storage/key-taken. The bytes go to
   the bucket before the row is written, so a row always has its object;
   an object whose row was rolled back stays, and the same bytes stored
   again find it.

   A file's kind is told by its bytes (groundwork-file-storage.kinds), never by its name or
   what the browser says; each call says which kinds it takes.

   Content-named, one file may be named by many rows of the app (two
   orders with the same PDF). A row names a file through a reference to
   :file/key ({:references {:to :file/key :on-delete :refuse :on-update :refuse
   :checked-at :statement}}: a delete of a named file is refused);
   delete-file! deletes a file only
   when none does (Postgres's reference refuses the row's delete, which
   groundwork-file-storage answers :still-named), its row and its object together. groundwork-file-storage
   knows no owners: a file's prefix is the app's to choose, and
   delete-unnamed-files! deletes every file under one that no row names.

   groundwork-file-storage writes to the app's log only, :log, and counts on the app's
   meter, :meter (groundwork-file-storage.metrics)."
  (:require
   [groundwork-file-storage.bucket :as bucket]
   [groundwork-file-storage.kinds :as kinds]
   [groundwork-file-storage.links :as links]
   [groundwork-file-storage.metrics :as metrics]
   [groundwork-file-storage.settings :as settings]
   [groundwork-file-storage.upload :as upload]
   [groundwork-postgres.api :as postgres]
   [clojure.string :as str]
   [groundwork-logs.api :as logs])
  (:import
   (java.security MessageDigest)
   (java.time Instant)))

(defn- make-query [file-name]
  (postgres/make-query-from-sql-file (str "groundwork_file_storage/" file-name)))

(def ^:private insert-file (make-query "insert_file.sql"))
(def ^:private find-file-row (make-query "find_file.sql"))
(def ^:private list-file-rows (make-query "list_files.sql"))
(def ^:private delete-file-row (make-query "delete_file.sql"))

;; -----------------------------------------------------------------------------
;; Settings and the table

(def settings-shape
  "make-file-storage's settings, every key required. See groundwork-file-storage.settings/settings-shape."
  settings/settings-shape)

(def upload-settings-shape
  "make-upload-handler's settings, every key required. See
   groundwork-file-storage.settings/upload-settings-shape."
  settings/upload-settings-shape)

(def known-content-types
  "Every content type groundwork-file-storage tells by its bytes: what :allowed-content-types
   may hold."
  kinds/known-content-types)

(def files-table
  "Every file stored: groundwork_file_storage.files, a map for groundwork-postgres, to pass with the app's
   tables. A row is never changed; delete-file! deletes it with its object,
   when no row of the app names it (a reference to :file/key)."
  {:table     :groundwork-file-storage/files
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
;; The file storage

(defn make-file-storage
  "A file storage from its settings and options ({:log log :meter meter}), every
   key required, checked first (:groundwork-file-storage/bad-settings, :groundwork-file-storage/bad-options);
   declares groundwork-file-storage's metrics on the meter. Returns the file storage, for every
   other function; stop it with stop-file-storage!."
  [file-storage-settings options]
  (settings/check-data-for-shape "The settings" :groundwork-file-storage/bad-settings settings/settings-shape file-storage-settings)
  (settings/check-data-for-shape "The options" :groundwork-file-storage/bad-options settings/options-shape options)
  (let [{:keys [log meter]} options]
    ;; a log groundwork-logs didn't make is refused now, not at the first failure
    (logs/with-context log {})
    (metrics/declare-metrics! meter)
    {:settings file-storage-settings
     :client   (bucket/make-client file-storage-settings)
     :log      log
     :meter    meter}))

(defn stop-file-storage!
  "Stops the file storage's client."
  [file-storage]
  (bucket/stop-client! (:client file-storage)))

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
   row. A key recorded with another sha256 throws :groundwork-file-storage/key-taken."
  [{:keys [settings client meter]} pool-or-transaction ^bytes bs key-prefix content-type]
  (let [sha256     (hash-bytes bs)
        object-key (make-key key-prefix sha256 content-type)
        recorded   (postgres/find-one-row pool-or-transaction find-file-row {:file/key object-key})
        check      (fn [row]
                     (when (not= sha256 (:file/sha256 row))
                       (throw (ex-info (str "The key " object-key " is recorded with other bytes")
                                       {:type :groundwork-file-storage/key-taken :object-key object-key})))
                     row)]
    (if recorded
      (do (metrics/count-stored! meter :already-stored)
          (check recorded))
      (let [put (try (bucket/put-object-once! client (:bucket settings) object-key bs content-type)
                     (catch Exception e
                       (metrics/count-bucket-failure! meter :store)
                       (throw e)))
            row (postgres/find-one-row pool-or-transaction insert-file
                                     {:file/key object-key :file/sha256 sha256 :file/size (alength bs)
                                      :file/content-type content-type})]
        (metrics/count-stored! meter (if (= :put put) :stored :already-stored))
        (remove-added (check row))))))

(defn- refuse-wrong-kind [meter]
  (metrics/count-refused! meter :wrong-kind)
  :wrong-kind)

(defn- store-if-allowed!
  "The row, or :wrong-kind."
  [file-storage pool-or-transaction bs {:keys [key-prefix allowed-content-types file-name]}]
  (let [content-type (kinds/find-content-type bs file-name)]
    (if (contains? allowed-content-types content-type)
      (put-and-record! file-storage pool-or-transaction bs key-prefix content-type)
      (refuse-wrong-kind (:meter file-storage)))))

(defn store-file!
  "Stores the bytes, named by their content, and records them, on a pool
   or in a transaction (then the row exists only if that commits; the
   object stays either way). Options, every key required, checked first:
   :key-prefix, the folder the key starts with (\"invoices\");
   :allowed-content-types, the kinds the bytes may be, a set of
   known-content-types; :file-name, the file's name or nil (only a .gltf
   needs it). Returns the file's row, #:file{:key :sha256 :size
   :content-type :stored-at}, the same for the same bytes. Bytes of
   another kind throw :groundwork-file-storage/wrong-kind, the bucket's failure
   :groundwork-file-storage/bucket-failed, a key recorded with other bytes :groundwork-file-storage/key-taken."
  [file-storage pool-or-transaction ^bytes bs options]
  (settings/check-data-for-shape "The options" :groundwork-file-storage/bad-options settings/store-options-shape options)
  (let [row (store-if-allowed! file-storage pool-or-transaction bs options)]
    (when (= :wrong-kind row)
      (throw (ex-info (str "The bytes are none of " (pr-str (sort (:allowed-content-types options))))
                      {:type :groundwork-file-storage/wrong-kind :found (kinds/find-content-type bs (:file-name options))})))
    row))

;; -----------------------------------------------------------------------------
;; Reading

(defn find-file
  "The file's row, or nil when no file has that key."
  [pool-or-transaction object-key]
  (postgres/find-one-row pool-or-transaction find-file-row {:file/key object-key}))

(defn list-files
  "The files' rows, by key. Options, every key required: :key-prefix
   (\"\" for all), :after-key (nil to start at the first; the last key of
   the page before), :limit."
  [pool-or-transaction options]
  (let [{:keys [key-prefix after-key limit]}
        (settings/check-data-for-shape "The options" :groundwork-file-storage/bad-options settings/list-options-shape options)]
    (postgres/find-rows pool-or-transaction list-file-rows {:key-prefix key-prefix :after-key after-key :limit limit})))

(defn read-file-bytes
  "The file's bytes from the bucket, or nil when the bucket has no object
   at the key; the bucket's failure throws :groundwork-file-storage/bucket-failed."
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
  ;; groundwork-file-storage's own, never handed to app code: keeps no work
  (postgres/run-in-transaction! pool {:read-only? false :log nil}
                              (fn [transaction]
                                (when-let [row (postgres/find-one-row transaction delete-file-row {:file/key object-key})]
                                  (try (bucket/delete-object! client (:bucket settings) object-key)
                                       (catch Exception e
                                         (metrics/count-bucket-failure! meter :delete)
                                         (throw e)))
                                  row))))

(defn delete-file!
  "Deletes a file: its row and its object, on a pool, in a transaction of
   its own (an object deleted can't come back with a rollback, so a
   transaction handed in is refused, :groundwork-file-storage/needs-a-pool: delete after the
   app's own rows are kept). Only when no row of the app names it through
   a reference to :file/key: then nothing is deleted. Returns :deleted,
   :still-named or :not-found; counted (groundwork_file_storage_files_deleted_total), a
   deletion written to the log (:info :groundwork-file-storage/file-deleted). The object is
   deleted before the row's delete is kept: a file stored again at once
   puts its bytes anew; a failed commit after it leaves a row without its
   object, which read-file-bytes answers nil. The bucket's failure throws
   :groundwork-file-storage/bucket-failed, the row kept."
  [{:keys [log meter] :as file-storage} pool object-key]
  (when (postgres/is-transaction? pool)
    (throw (ex-info (str "delete-file! runs in a transaction of its own: hand it the pool, after the app's transaction is kept"
                         " (delete-file-after-commit! keeps it for then)")
                    {:type :groundwork-file-storage/needs-a-pool :object-key object-key})))
  (let [outcome (try
                  (if-let [row (delete-row-and-object! file-storage pool object-key)]
                    (do (log :info :groundwork-file-storage/file-deleted {:groundwork-file-storage/key object-key :groundwork-file-storage/size (:file/size row)})
                        :deleted)
                    :not-found)
                  (catch Exception e
                    (if (is-foreign-key-violation? e) :still-named (throw e))))]
    (metrics/count-deleted! meter outcome)
    outcome))

(defn delete-file-after-commit!
  "Keeps delete-file! on the pool to run once the app's transaction
   commits (groundwork-postgres's run-after-commit!), so a handler deleting the rows
   that named a file deletes the file too, after they are kept; never
   when the transaction rolls back. Returns nil: the outcome comes later,
   counted and logged as delete-file!'s (:still-named when a row still
   names it), and a failure of the bucket is written by groundwork-postgres to the log
   the app's transaction carries (groundwork-postgres's :log, a message's with its
   request id), :groundwork-postgres/after-commit-failed. Lost if the process dies between
   the commit and the delete: delete-unnamed-files! in a background job
   finds what is left. Handed a pool for the transaction, groundwork-postgres refuses,
   :groundwork-postgres/not-a-transaction.

     (postgres/run-statement! transaction delete-invoice {:invoice/id id})
     (file-storage/delete-file-after-commit! file-storage pool transaction invoice-key)"
  [file-storage pool transaction object-key]
  (postgres/run-after-commit! transaction #(delete-file! file-storage pool object-key)))

(defn delete-unnamed-files!
  "Deletes every file under a prefix that no row of the app names
   (delete-file! each, a page at a time): for a background job after the
   app deletes its rows. Options, every key required: :key-prefix (\"\"
   for all), :page-size. Returns {:deleted n :still-named n}."
  [file-storage pool options]
  (let [{:keys [key-prefix page-size]}
        (settings/check-data-for-shape "The options" :groundwork-file-storage/bad-options settings/delete-unnamed-options-shape options)]
    (loop [after-key nil counts {:deleted 0 :still-named 0}]
      (let [rows (postgres/find-rows pool list-file-rows {:key-prefix key-prefix :after-key after-key :limit page-size})
            counts (reduce (fn [counts {:file/keys [key]}]
                             (let [outcome (delete-file! file-storage pool key)]
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
   :groundwork-file-storage/no-public-url when it is nil."
  [{:keys [settings]} object-key]
  (links/make-public-link settings object-key))

;; -----------------------------------------------------------------------------
;; Uploads

(defn make-upload-handler
  "A Ring handler for an upload through the server: a multipart POST, one
   part per file (groundwork-file-storage.upload says the answer). The settings
   (upload-settings-shape: :key-prefix :allowed-content-types
   :max-file-bytes :max-files) and the options (:pool, :shaper, the app's,
   and :may-upload?, (fn [request] true-or-false), asked before the body
   is read), every key required, are checked first. The app puts it at
   its own path, behind its own checks."
  [file-storage upload-settings options]
  (settings/check-data-for-shape "The settings" :groundwork-file-storage/bad-settings settings/upload-settings-shape upload-settings)
  (settings/check-data-for-shape "The options" :groundwork-file-storage/bad-options settings/upload-options-shape options)
  (let [{:keys [pool shaper may-upload?]} options
        {:keys [key-prefix allowed-content-types]} upload-settings]
    (upload/make-upload-handler
     (assoc upload-settings
            :shaper                shaper
            :may-upload?         may-upload?
            :log                 (:log file-storage)
            :count-refused!      #(metrics/count-refused! (:meter file-storage) %)
            ;; groundwork-file-storage's own, never handed to app code: keeps no work
            :run-in-transaction! #(postgres/run-in-transaction! pool {:read-only? false :log nil} %)
            :store-part!         (fn [transaction {:keys [file-name bytes]}]
                                   (store-if-allowed! file-storage transaction bytes {:key-prefix            key-prefix
                                                                               :allowed-content-types allowed-content-types
                                                                               :file-name             file-name}))))))

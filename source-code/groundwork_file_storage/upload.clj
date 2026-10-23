(ns groundwork-file-storage.upload
  "Uploads through the server: a Ring handler for a multipart POST, one
   part per file. Who may upload is asked first, before the body is read;
   then each part is read, at most :max-file-bytes of it, its kind told by
   its bytes, and stored (groundwork-file-storage.api's store-file!, every row in one
   transaction). The answer, JSON by the app's shaper:

     200 {\"stored\":   [{\"field\":\"photo\",\"file-name\":\"door.png\",\"key\":\"orders/photos/….png\",
                         \"sha256\":\"…\",\"size\":48213,\"content-type\":\"image/png\"}],
          \"rejected\": [{\"field\":\"notes\",\"file-name\":\"notes.html\",\"reason\":\"wrong-kind\"}]}

   reasons: wrong-kind (no allowed content type), too-large (more than
   :max-file-bytes), not-a-file (a form field, not a file). Refusals of the
   whole request, nothing stored: 403 when the app's :may-upload? says no,
   400 for a body that isn't multipart/form-data or can't be read, 413 for
   more parts than :max-files, 503 when the bucket or the database
   failed (written to the log, :groundwork-file-storage/upload-failed). Never cached."
  (:require
   [ring.middleware.multipart-params :as multipart]
   [groundwork-shaped-json.api :as shaped-json])
  (:import
   (java.io ByteArrayOutputStream InputStream)))

(defn- answer [shaper status data]
  {:status  status
   :headers {"content-type" "application/json" "cache-control" "no-store"}
   :body    (shaped-json/turn-data-into-json-text shaper data)})

(defn- read-at-most
  "The stream's bytes, or :too-large when it holds more than max-bytes;
   then the rest is read and dropped, so the next part can be read."
  [^InputStream stream max-bytes]
  (let [out    (ByteArrayOutputStream.)
        buffer (byte-array 65536)]
    (loop [total 0]
      (let [n (.read stream buffer)]
        (cond
          (neg? n)                    (.toByteArray out)
          (> (+ total n) max-bytes)   (do (while (pos? (.read stream buffer)))
                                          :too-large)
          :else                       (do (.write out buffer 0 n)
                                          (recur (+ total n))))))))

(defn- make-part-store
  "ring's :store for a file part: its name and its bytes, or :too-large."
  [max-file-bytes]
  (fn [{:keys [filename stream]}]
    {:groundwork-file-storage/file-name filename
     :groundwork-file-storage/bytes     (read-at-most stream max-file-bytes)}))

(defn- list-parts
  "Every part as [field value], in order, a field holding several parts
   once per part."
  [params]
  (for [[field value] params
        part (if (vector? value) value [value])]
    [field part]))

(defn- read-parts
  "The request's parts, or a refusal of the whole request: :not-multipart,
   :too-many-files, :unreadable."
  [request {:keys [max-file-bytes max-files]}]
  (if-not (re-find #"(?i)^multipart/form-data" (str (get-in request [:headers "content-type"])))
    :not-multipart
    (try
      (vec (list-parts (multipart/parse-multipart-params request {:store          (make-part-store max-file-bytes)
                                                                  :max-file-count max-files
                                                                  :encoding       "UTF-8"})))
      (catch clojure.lang.ExceptionInfo e
        (if (:max-file-count (ex-data e)) :too-many-files :unreadable))
      (catch Exception _ :unreadable))))

(defn- sort-part
  "A part as {:field :file-name :bytes} to store, or as a rejected one."
  [[field part]]
  (cond
    (not (map? part))                       {:rejected {:field field :file-name nil :reason :not-a-file}}
    (= :too-large (:groundwork-file-storage/bytes part))      {:rejected {:field field :file-name (:groundwork-file-storage/file-name part) :reason :too-large}}
    :else                                   {:to-store {:field field :file-name (:groundwork-file-storage/file-name part) :bytes (:groundwork-file-storage/bytes part)}}))

(defn make-upload-handler
  "The Ring handler (see the namespace). store-part! is
   (fn [transaction {:keys [field file-name bytes]}]) → the file's row, or
   :wrong-kind; run-in-transaction! is (fn [f]); log and count-refused!
   are groundwork-file-storage's."
  [{:keys [shaper may-upload? log run-in-transaction! store-part! count-refused! max-files] :as upload}]
  (fn [request]
    (if-not (may-upload? request)
      (answer shaper 403 {:error "Not allowed."})
      (let [parts (read-parts request upload)]
        (case parts
          :not-multipart  (answer shaper 400 {:error "Send the files as multipart/form-data, one part per file."})
          :unreadable     (answer shaper 400 {:error "The body couldn't be read as multipart/form-data."})
          :too-many-files (answer shaper 413 {:error (str "Too many files: at most " max-files " in one upload.")})
          (let [sorted   (map sort-part parts)
                rejected (keep :rejected sorted)]
            (doseq [{:keys [reason]} rejected :when (= :too-large reason)] (count-refused! :too-large))
            (try
              (let [results (run-in-transaction!
                             (fn [transaction]
                               (mapv (fn [{:keys [field file-name] :as part}]
                                       (let [row (store-part! transaction part)]
                                         (if (= :wrong-kind row)
                                           {:rejected {:field field :file-name file-name :reason :wrong-kind}}
                                           {:stored {:field        field
                                                     :file-name    file-name
                                                     :key          (:file/key row)
                                                     :sha256       (:file/sha256 row)
                                                     :size         (:file/size row)
                                                     :content-type (:file/content-type row)}})))
                                     (keep :to-store sorted))))]
                (answer shaper 200 {:stored   (vec (keep :stored results))
                                  :rejected (into (vec rejected) (keep :rejected results))}))
              (catch Exception e
                (log :error :groundwork-file-storage/upload-failed {:error e})
                (answer shaper 503 {:error "The files couldn't be stored; none of this upload's were recorded."})))))))))

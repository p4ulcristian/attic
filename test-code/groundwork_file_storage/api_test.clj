(ns groundwork-file-storage.api-test
  "The kinds, the settings and the links' shape run everywhere. The rest
   runs against a real S3 API and a real Postgres when FILE_STORAGE_TEST_S3_ENDPOINT
   and FILE_STORAGE_TEST_PG_PORT are set (see README.md); says it was skipped
   otherwise."
  (:require
   [groundwork-file-storage.api :as file-storage]
   [groundwork-file-storage.kinds :as kinds]
   [groundwork-postgres.api :as postgres]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.credentials :as credentials]
   [jsonista.core :as json]
   [groundwork-logs.api :as logs]
   [groundwork-metrics.api :as metrics]
   [groundwork-settings.api :as settings]
   [groundwork-shaped-json.api :as shaped-json])
  (:import
   (java.io ByteArrayInputStream ByteArrayOutputStream)
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
   (java.nio.charset StandardCharsets)))

(def ^:private s3-endpoint (System/getenv "FILE_STORAGE_TEST_S3_ENDPOINT"))
(def ^:private pg-port (some-> (System/getenv "FILE_STORAGE_TEST_PG_PORT") parse-long))

(def ^:private lines (atom []))

(def ^:private log
  (logs/log-function (logs/make-logger {:lowest-level :debug :format :json :errors-kept 100
                                               :write-to     #(swap! lines conj (json/read-value %))})))

(defn- make-meter []
  (metrics/make-meter {:jvm-metrics false :most-series-per-metric 1000} {:log log}))

(def ^:private shaper
  (shaped-json/make-shaper-from-settings
   {:most-shapes-kept 1000
    :json-limits      {:max-nesting-depth 1000 :max-number-length 1000 :max-string-length 20000000
                       :max-name-length 50000 :max-document-length nil :max-token-count nil
                       :max-writing-nesting-depth 1000}}))

(defn- make-settings [link-expiry-seconds]
  {:endpoint            (or s3-endpoint "http://127.0.0.1:1")
   :bucket              "file-storage-test"
   :access-key          (settings/make-secret :groundwork-file-storage/access-key (or (System/getenv "FILE_STORAGE_TEST_S3_ACCESS_KEY") "key"))
   :secret-key          (settings/make-secret :groundwork-file-storage/secret-key (or (System/getenv "FILE_STORAGE_TEST_S3_SECRET_KEY") "secret"))
   :region              "us-east-1"
   :public-url          "https://files.shop.example"
   :link-expiry-seconds link-expiry-seconds
   :connect-timeout-ms  2000
   :request-timeout-ms  10000
   :retries             {:max-retries 2 :first-wait-ms 100 :max-wait-ms 400}})

(def ^:private png
  (byte-array (concat [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A] (map byte (range 64)))))

(def ^:private pdf
  (.getBytes "%PDF-1.7\n1 0 obj << >> endobj\n%%EOF\n" StandardCharsets/UTF_8))

(def ^:private html
  (.getBytes "<!doctype html><script>alert(1)</script>" StandardCharsets/UTF_8))

;; -----------------------------------------------------------------------------
;; Everywhere

(deftest kinds-are-told-by-the-bytes
  (is (= "image/png" (kinds/find-content-type png "anything.txt")))
  (is (= "application/pdf" (kinds/find-content-type pdf nil)))
  (is (= "image/jpeg" (kinds/find-content-type (byte-array (map unchecked-byte [0xFF 0xD8 0xFF 0xE0 0 0])) nil)))
  (is (nil? (kinds/find-content-type html "page.png")) "markup is no kind, whatever its name says")
  (is (nil? (kinds/find-content-type (.getBytes "<svg xmlns='http://www.w3.org/2000/svg'/>") "icon.svg")))
  (is (= "model/gltf+json" (kinds/find-content-type (.getBytes "{\"asset\":{}}") "door.gltf")))
  (is (nil? (kinds/find-content-type (.getBytes "{\"asset\":{}}") "door.json")) "JSON is a .gltf only by its name")
  (is (nil? (kinds/find-content-type (byte-array 0) nil))))

(deftest settings-are-every-key-written-out
  (let [problems (:errors (ex-data (try (file-storage/make-file-storage (dissoc (make-settings 60) :region :retries)
                                                          {:log log :meter (make-meter)})
                                        (catch Exception e e))))]
    (is (= {:region ["missing required key"] :retries ["missing required key"]} problems)))
  (is (= :groundwork-file-storage/bad-settings
         (:type (ex-data (try (file-storage/make-file-storage (assoc (make-settings 60) :region "auto") {:log log :meter (make-meter)})
                              (catch Exception e e)))))
      "a region aws-api has no endpoint for is refused at the start")
  (testing "the bucket's key is two secrets: written out as text, each is refused, and the text is never said"
    (let [refused (ex-data (try (file-storage/make-file-storage (assoc (make-settings 60) :access-key "written-out-access" :secret-key "written-out-secret")
                                                  {:log log :meter (make-meter)})
                                (catch Exception e e)))]
      (is (= {:access-key ["should be a #secret, read from the settings' secrets source"]
              :secret-key ["should be a #secret, read from the settings' secrets source"]}
             (:errors refused)))
      (is (not (re-find #"written-out" (pr-str refused))))))
  (is (= {:key-prefix ["should have no . or .. segment"]}
         (:errors (ex-data (try (file-storage/store-file! nil nil png {:key-prefix "a/../b" :allowed-content-types #{"image/png"} :file-name nil})
                                (catch Exception e e)))))))

(deftest links-say-the-bucket-the-key-and-the-expiry
  (let [a    (file-storage/make-file-storage (make-settings 300) {:log log :meter (make-meter)})
        link (file-storage/make-signed-link a "orders/a b.png")]
    (is (str/includes? link "/attic-test/orders/a%20b.png?"))
    (is (str/includes? link "X-Amz-Expires=300"))
    (is (str/includes? link (str "X-Amz-Credential=" (or (System/getenv "FILE_STORAGE_TEST_S3_ACCESS_KEY") "key") "%2F"))
        "the access key, revealed, names the key that signed")
    (is (re-find #"X-Amz-Signature=[0-9a-f]{64}$" link))
    (is (= "https://files.shop.example/orders/a%20b.png" (file-storage/make-public-link a "orders/a b.png")))
    (is (= :groundwork-file-storage/no-public-url
           (:type (ex-data (try (file-storage/make-public-link (assoc-in a [:settings :public-url] nil) "x.png")
                                (catch Exception e e))))))
    (file-storage/stop-file-storage! a)))

;; -----------------------------------------------------------------------------
;; Against S3 and Postgres

(defn- make-pool []
  (postgres/start-pool! {:host "localhost" :port pg-port :database "postgres" :user (System/getenv "FILE_STORAGE_TEST_PG_USER")
                       :password nil :tls :off :tls-root-cert nil :pool-size 4
                       :connection-wait-ms 30000 :connection-check-wait-ms 5000
                       :connection-max-lifetime-ms 1800000 :connection-keepalive-every-ms 120000
                       :leak-warning-after-ms :none
                       :connect-timeout-seconds 10 :read-timeout-seconds :none :tcp-keepalive? false
                       :statement-timeout-ms 5000 :lock-timeout-ms :none :idle-in-transaction-timeout-ms :none
                       :time-zone "UTC"
                       :metric-buckets {:query-seconds [0.01 0.1 1] :connection-acquire-seconds [0.01 0.1 1]
                                        :connection-usage-seconds [0.01 0.1 1]}}
                      {:log log :meter (make-meter)}))

(defn- make-tables-afresh! [pool]
  (postgres/run-statement! pool (postgres/make-query-from-sql-text "drop" "DROP SCHEMA IF EXISTS groundwork_file_storage CASCADE; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE") {})
  @(:slow-part (postgres/migrate! pool {:tables [file-storage/files-table] :extensions [] :functions [] :views []} []
                                {:instant-part {:statement-timeout-ms 5000 :lock-timeout-ms 5000}
                                 :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}
                                 :log          (fn [_ _ _])})))

(defn- make-bucket-afresh!
  "The test bucket, emptied, through aws-api directly."
  [{:keys [endpoint bucket access-key secret-key]}]
  (let [uri    (URI. endpoint)
        client (aws/client {:api :s3 :region "us-east-1"
                            :endpoint-override {:protocol (keyword (.getScheme uri)) :hostname (.getHost uri) :port (.getPort uri)}
                            :credentials-provider (credentials/basic-credentials-provider
                                                   {:access-key-id (settings/reveal access-key) :secret-access-key (settings/reveal secret-key)})})]
    (aws/invoke client {:op :CreateBucket :request {:Bucket bucket}})
    (doseq [{:keys [Key]} (:Contents (aws/invoke client {:op :ListObjectsV2 :request {:Bucket bucket}}))]
      (aws/invoke client {:op :DeleteObject :request {:Bucket bucket :Key Key}}))
    (aws/stop client)))

(defn- fetch [link]
  (let [answer (.send (HttpClient/newHttpClient) (.build (HttpRequest/newBuilder (URI. link)))
                      (HttpResponse$BodyHandlers/ofByteArray))]
    {:status (.statusCode answer) :body (.body answer)}))

(defn- read-counter [a metric-name]
  (->> (str/split-lines (metrics/write-metrics-text (:meter a)))
       (filter #(str/starts-with? % metric-name))
       (map #(let [[series value] (str/split % #" ")] [series (parse-double value)]))
       (into {})))

(defn- make-multipart-request
  "A POST with these parts: [field file-name-or-nil bytes-or-text]."
  [parts]
  (let [boundary "file-storage-test-boundary"
        out      (ByteArrayOutputStream.)
        write    #(.write out (.getBytes ^String % StandardCharsets/UTF_8))]
    (doseq [[field file-name content] parts]
      (write (str "--" boundary "\r\n"))
      (if file-name
        (write (str "Content-Disposition: form-data; name=\"" field "\"; filename=\"" file-name "\"\r\n"
                    "Content-Type: application/octet-stream\r\n\r\n"))
        (write (str "Content-Disposition: form-data; name=\"" field "\"\r\n\r\n")))
      (if (string? content) (write content) (.write out ^bytes content))
      (write "\r\n"))
    (write (str "--" boundary "--\r\n"))
    (let [body (.toByteArray out)]
      {:request-method :post
       :uri            "/files"
       :headers        {"content-type"   (str "multipart/form-data; boundary=" boundary)
                        "content-length" (str (alength body))}
       :body           (ByteArrayInputStream. body)})))

(defn- read-answer [{:keys [status body]}]
  [status (json/read-value body)])

(deftest files-in-a-real-bucket
  (if-not (and s3-endpoint pg-port)
    (println "groundwork-file-storage.api-test: the bucket tests skipped, FILE_STORAGE_TEST_S3_ENDPOINT or FILE_STORAGE_TEST_PG_PORT is not set")
    (let [pool (make-pool)
          a    (file-storage/make-file-storage (make-settings 60) {:log log :meter (make-meter)})]
      (try
        (make-tables-afresh! pool)
        (make-bucket-afresh! (make-settings 60))

        (testing "a file is named by its content and recorded"
          (let [row (file-storage/store-file! a pool png {:key-prefix "orders/photos" :allowed-content-types #{"image/png"} :file-name nil})
                sha (-> (java.security.MessageDigest/getInstance "SHA-256") (.digest png) (->> (map #(format "%02x" %)) (apply str)))]
            (is (= (str "orders/photos/" (subs sha 0 8) "-" (subs sha 8 12) "-" (subs sha 12 16) "-" (subs sha 16 20) "-"
                        (subs sha 20 32) ".png")
                   (:file/key row)))
            (is (= {:file/sha256 sha :file/size (alength png) :file/content-type "image/png"}
                   (select-keys row [:file/sha256 :file/size :file/content-type])))
            (is (some? (:file/stored-at row)))
            (is (= (seq png) (seq (file-storage/read-file-bytes a (:file/key row)))) "the bucket holds the bytes")
            (is (= row (file-storage/find-file pool (:file/key row))))))

        (testing "the same bytes are the same key, stored once"
          (let [first-row  (file-storage/store-file! a pool png {:key-prefix "orders/photos" :allowed-content-types #{"image/png"} :file-name "again.png"})
                second-row (file-storage/store-file! a pool png {:key-prefix "orders/photos" :allowed-content-types #{"image/png"} :file-name nil})]
            (is (= first-row second-row))
            (is (= 1 (count (file-storage/list-files pool {:key-prefix "orders/" :after-key nil :limit 10}))))
            (is (= {"groundwork_file_storage_files_stored_total{outcome=\"stored\"}"         1.0
                    "groundwork_file_storage_files_stored_total{outcome=\"already-stored\"}" 2.0}
                   (read-counter a "groundwork_file_storage_files_stored_total")))))

        (testing "an object whose row was rolled back is found again, not written over"
          (let [key (:file/key (try (postgres/run-in-transaction! pool {:read-only? false :log nil}
                                                                (fn [transaction]
                                                                  (let [row (file-storage/store-file! a transaction pdf {:key-prefix "invoices" :allowed-content-types #{"application/pdf"} :file-name nil})]
                                                                    (throw (ex-info "the caller's work failed" {:row row})))))
                                    (catch Exception e (:row (ex-data e)))))]
            (is (nil? (file-storage/find-file pool key)) "the row went with the transaction")
            (is (some? (file-storage/read-file-bytes a key)) "the object stayed")
            (is (= key (:file/key (file-storage/store-file! a pool pdf {:key-prefix "invoices" :allowed-content-types #{"application/pdf"} :file-name nil}))))
            (is (= [key] (mapv :file/key (file-storage/list-files pool {:key-prefix "invoices" :after-key nil :limit 10}))))))

        (testing "bytes of a kind the call doesn't allow are refused, nothing stored"
          (is (= :groundwork-file-storage/wrong-kind
                 (:type (ex-data (try (file-storage/store-file! a pool html {:key-prefix "x" :allowed-content-types #{"image/png"} :file-name "a.png"})
                                      (catch Exception e e))))))
          (is (= :groundwork-file-storage/wrong-kind
                 (:type (ex-data (try (file-storage/store-file! a pool pdf {:key-prefix "x" :allowed-content-types #{"image/png"} :file-name nil})
                                      (catch Exception e e))))))
          (is (= [] (file-storage/list-files pool {:key-prefix "x/" :after-key nil :limit 10}))))

        (testing "a signed link reads the file, a changed one doesn't, an expired one doesn't"
          (let [key   (:file/key (file-storage/find-file pool (:file/key (first (file-storage/list-files pool {:key-prefix "orders/" :after-key nil :limit 1})))))
                link  (file-storage/make-signed-link a key)
                short (file-storage/make-file-storage (make-settings 1) {:log log :meter (make-meter)})
                soon  (file-storage/make-signed-link short key)]
            (is (= [200 (seq png)] ((juxt :status (comp seq :body)) (fetch link))))
            (is (= 403 (:status (fetch (str/replace link #"X-Amz-Expires=60" "X-Amz-Expires=600")))))
            (is (= 403 (:status (fetch (str/replace link #"/attic-test/orders/" "/attic-test/invoices/")))))
            (is (= 200 (:status (fetch soon))))
            (Thread/sleep 2500)
            (is (= 403 (:status (fetch soon))) "past its expiry")
            (file-storage/stop-file-storage! short)))

        (testing "an upload stores the files of an allowed kind and size, and says which it refused"
          (let [handler (file-storage/make-upload-handler a {:key-prefix "uploads" :allowed-content-types #{"image/png" "application/pdf"}
                                                      :max-file-bytes 1000 :max-files 4}
                                                   {:pool pool :shaper shaper :may-upload? (constantly true)})
                big     (byte-array (concat (seq pdf) (repeat 2000 (byte 32))))
                [status {:strs [stored rejected]}]
                (read-answer (handler (make-multipart-request [["photo" "door.png" png]
                                                               ["page" "page.png" html]
                                                               ["scan" "scan.pdf" big]
                                                               ["note" nil "hello"]])))]
            (is (= 200 status))
            (is (= [{"field" "photo" "file-name" "door.png" "content-type" "image/png" "size" (alength png)}]
                   (mapv #(select-keys % ["field" "file-name" "content-type" "size"]) stored)))
            (is (str/starts-with? (get (first stored) "key") "uploads/"))
            (is (= #{{"field" "page" "file-name" "page.png" "reason" "wrong-kind"}
                     {"field" "scan" "file-name" "scan.pdf" "reason" "too-large"}
                     {"field" "note" "file-name" nil "reason" "not-a-file"}}
                   (set rejected)))
            (is (= [] (file-storage/list-files pool {:key-prefix "uploads/scan" :after-key nil :limit 10})))
            (is (= {"groundwork_file_storage_files_refused_total{reason=\"wrong-kind\"}" 3.0
                    "groundwork_file_storage_files_refused_total{reason=\"too-large\"}"  1.0}
                   (read-counter a "groundwork_file_storage_files_refused_total")))

            (testing "more parts than :max-files refuse the whole upload"
              (is (= 413 (first (read-answer (handler (make-multipart-request (repeat 5 ["photo" "p.png" png]))))))))
            (testing "a body that isn't multipart is refused"
              (is (= 400 (first (read-answer (handler {:request-method :post :headers {"content-type" "application/json"}
                                                       :body (ByteArrayInputStream. (.getBytes "{}"))})))))))
          (testing "who may upload is asked before the body is read"
            (let [read?   (atom false)
                  handler (file-storage/make-upload-handler a {:key-prefix "uploads" :allowed-content-types #{"image/png"}
                                                        :max-file-bytes 1000 :max-files 4}
                                                     {:pool pool :shaper shaper :may-upload? (constantly false)})
                  request (update (make-multipart-request [["photo" "door.png" png]]) :body
                                  (fn [body] (proxy [java.io.InputStream] []
                                               (read ([] (reset! read? true) (.read ^java.io.InputStream body))
                                                 ([bs off len] (reset! read? true) (.read ^java.io.InputStream body bs off len))))))]
              (is (= [403 {"error" "Not allowed."}] (read-answer (handler request))))
              (is (false? @read?)))))

        (testing "a bucket that can't be reached fails the store, after its retries"
          (let [gone (file-storage/make-file-storage (assoc (make-settings 60) :endpoint "http://127.0.0.1:1") {:log log :meter (make-meter)})]
            (is (= :groundwork-file-storage/bucket-failed
                   (:type (ex-data (try (file-storage/store-file! gone pool (byte-array (concat (seq png) [1 2 3]))
                                                           {:key-prefix "gone" :allowed-content-types #{"image/png"} :file-name nil})
                                        (catch Exception e e))))))
            (is (= [] (file-storage/list-files pool {:key-prefix "gone/" :after-key nil :limit 10})) "no row without its object")
            (file-storage/stop-file-storage! gone)))
        (finally
          (file-storage/stop-file-storage! a)
          (postgres/stop-pool! pool))))))

;; -----------------------------------------------------------------------------
;; Deleting

(def ^:private orders
  "An app's table whose rows name a file, through a reference to :file/key."
  {:table      :orders
   :columns    [[:order/id :int {:primary-key? true}]
                [:order/invoice-key :string {:references {:to :file/key :on-delete :refuse :on-update :refuse
                                                          :checked-at :statement}}]]
   :indexes    [{:columns [[:order/invoice-key :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}]
   :uniques    []
   :checks     []
   :references []
   :triggers   []
   :gates      nil
   :on-change  nil})

(defn- run-sql! [pool text] (postgres/run-statement! pool (postgres/make-query-from-sql-text "test" text) {}))

(deftest deleting-files-in-a-real-bucket
  (if-not (and s3-endpoint pg-port)
    (println "groundwork-file-storage.api-test: the delete tests skipped, FILE_STORAGE_TEST_S3_ENDPOINT or FILE_STORAGE_TEST_PG_PORT is not set")
    (let [pool (make-pool)
          a    (file-storage/make-file-storage (make-settings 60) {:log log :meter (make-meter)})
          pdf? {:key-prefix "invoices" :allowed-content-types #{"application/pdf"} :file-name nil}]
      (try
        (run-sql! pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public")
        (make-tables-afresh! pool)
        @(:slow-part (postgres/migrate! pool {:tables [file-storage/files-table orders] :extensions [] :functions [] :views []} []
                                      {:instant-part {:statement-timeout-ms 5000 :lock-timeout-ms 5000}
                                       :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}
                                       :log          (fn [_ _ _])}))
        (make-bucket-afresh! (make-settings 60))
        (let [key (:file/key (file-storage/store-file! a pool pdf pdf?))]
          (run-sql! pool (str "INSERT INTO orders VALUES (1, '" key "'), (2, '" key "')"))
          (testing "a file two rows name, one content for two orders, is kept while either names it"
            (is (= :still-named (file-storage/delete-file! a pool key)))
            (run-sql! pool "DELETE FROM orders WHERE order__id = 1")
            (is (= :still-named (file-storage/delete-file! a pool key)))
            (is (some? (file-storage/find-file pool key)))
            (is (= (seq pdf) (seq (file-storage/read-file-bytes a key))) "the object stays with its row"))
          (testing "named by none: its row and its object go"
            (run-sql! pool "DELETE FROM orders")
            (reset! lines [])
            (is (= :deleted (file-storage/delete-file! a pool key)))
            (is (nil? (file-storage/find-file pool key)))
            (is (nil? (file-storage/read-file-bytes a key)))
            (is (= [["info" "groundwork-file-storage/file-deleted" key]]
                   (map (juxt #(get % "level") #(get % "event") #(get-in % ["data" "groundwork-file-storage/key"])) @lines))))
          (testing "a key no file has"
            (is (= :not-found (file-storage/delete-file! a pool key))))
          (testing "the same bytes stored again are put anew"
            (is (= key (:file/key (file-storage/store-file! a pool pdf pdf?))))
            (is (= (seq pdf) (seq (file-storage/read-file-bytes a key)))))
          (testing "a transaction is refused: a deleted object can't come back with a rollback"
            (is (= :groundwork-file-storage/needs-a-pool
                   (:type (ex-data (try (postgres/run-in-transaction! pool {:read-only? false :log nil} #(file-storage/delete-file! a % key))
                                        (catch Exception e e)))))))
          (testing "kept for after the app's transaction: never after a rollback, deleted after a commit"
            (run-sql! pool (str "INSERT INTO orders VALUES (5, '" key "')"))
            (is (thrown? clojure.lang.ExceptionInfo
                         (postgres/run-in-transaction! pool {:read-only? false :log log}
                           (fn [transaction]
                             (postgres/run-statement! transaction (postgres/make-query-from-sql-text "test" "DELETE FROM orders WHERE order__id = 5") {})
                             (is (nil? (file-storage/delete-file-after-commit! a pool transaction key)))
                             (throw (ex-info "undo" {}))))))
            (is (some? (file-storage/find-file pool key)) "rolled back: the row and the file stay")
            (postgres/run-in-transaction! pool {:read-only? false :log log}
              (fn [transaction]
                (postgres/run-statement! transaction (postgres/make-query-from-sql-text "test" "DELETE FROM orders WHERE order__id = 5") {})
                (file-storage/delete-file-after-commit! a pool transaction key)
                (is (some? (file-storage/find-file transaction key)) "not before the commit")))
            (is (nil? (file-storage/find-file pool key)) "its row and its object gone after the commit")
            (is (nil? (file-storage/read-file-bytes a key)))
            (is (= key (:file/key (file-storage/store-file! a pool pdf pdf?))) "stored again for the tests below"))
          (testing "every file under a prefix that no row names, a page at a time"
            (let [named (:file/key (file-storage/store-file! a pool png {:key-prefix "invoices" :allowed-content-types #{"image/png"} :file-name nil}))]
              (file-storage/store-file! a pool (byte-array (concat (seq png) [7])) {:key-prefix "invoices" :allowed-content-types #{"image/png"} :file-name nil})
              (file-storage/store-file! a pool png {:key-prefix "elsewhere" :allowed-content-types #{"image/png"} :file-name nil})
              (run-sql! pool (str "INSERT INTO orders VALUES (3, '" named "')"))
              (is (= {:deleted 2 :still-named 1} (file-storage/delete-unnamed-files! a pool {:key-prefix "invoices/" :page-size 1})))
              (is (= [named] (map :file/key (file-storage/list-files pool {:key-prefix "invoices/" :after-key nil :limit 10}))))
              (is (= 1 (count (file-storage/list-files pool {:key-prefix "elsewhere/" :after-key nil :limit 10}))) "another prefix is left")))
          (testing "counted by outcome"
            (is (= {"groundwork_file_storage_files_deleted_total{outcome=\"deleted\"}"     4.0
                    "groundwork_file_storage_files_deleted_total{outcome=\"still-named\"}" 3.0
                    "groundwork_file_storage_files_deleted_total{outcome=\"not-found\"}"   1.0}
                   (read-counter a "groundwork_file_storage_files_deleted_total")))))
        (finally
          (run-sql! pool "DROP TABLE IF EXISTS public.orders")
          (file-storage/stop-file-storage! a)
          (postgres/stop-pool! pool))))))

(ns attic.bucket
  "The bucket, through cognitect's aws-api (S3 only): a client from the
   settings, an object put once and never written over, an object read.

   aws-api's own http client has a fixed connect timeout (10 s) and no
   request timeout; attic hands it one of its own, java.net.http's with the
   settings' two timeouts, which sends through aws-api's java.net.http
   submit. An object is put once, read, or deleted. A request the bucket was too busy for (aws-api's own rule), or
   that never connected (nothing was sent, so sending again is safe), is
   retried by the settings' :retries."
  (:require
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.credentials :as credentials]
   [cognitect.aws.endpoint :as endpoint]
   [cognitect.aws.http :as aws-http]
   [cognitect.aws.http.java :as aws-http-java]
   [cognitect.aws.retry :as retry]
   [safe.api :as safe])
  (:import
   (java.io InputStream)
   (java.net ConnectException URI)
   (java.net.http HttpClient HttpClient$Redirect HttpConnectTimeoutException)
   (java.time Duration)))

(defn- make-http-client
  "java.net.http's client, its connect timeout the settings', every
   request's timeout the settings', redirects never followed (as aws-api's
   own)."
  [{:keys [connect-timeout-ms request-timeout-ms]}]
  (let [java-client (-> (HttpClient/newBuilder)
                        (.connectTimeout (Duration/ofMillis connect-timeout-ms))
                        (.followRedirects HttpClient$Redirect/NEVER)
                        (.build))]
    (reify aws-http/HttpClient
      (-submit [_ request channel]
        (aws-http-java/submit java-client (assoc request :timeout-msec request-timeout-ms) channel))
      (-stop [_] nil))))

(defn- did-never-connect?
  "Whether the request never reached the bucket: the connection timed out
   or was refused. aws-api may wrap the cause."
  [answer]
  (loop [^Throwable t (:cognitect.aws/throwable answer)]
    (cond
      (nil? t) false
      (or (instance? HttpConnectTimeoutException t) (instance? ConnectException t)) true
      :else (recur (.getCause t)))))

(defn- is-retriable? [answer]
  (boolean (or (retry/default-retriable? answer) (did-never-connect? answer))))

(defn turn-endpoint-into-parts
  "\"https://host:port\" as aws-api's :endpoint-override."
  [endpoint-text]
  (let [uri    (URI. endpoint-text)
        scheme (.getScheme uri)
        port   (.getPort uri)]
    {:protocol (keyword scheme)
     :hostname (.getHost uri)
     :port     (if (neg? port) (if (= "https" scheme) 443 80) port)}))

(defn check-region
  "Throws when aws-api has no S3 endpoint for the region: every request
   would fail with \"No known endpoint.\"."
  [region]
  (when-not (endpoint/resolve :s3 (keyword region))
    (throw (ex-info (str "The region " (pr-str region) " is one aws-api doesn't know: write one it does (R2 checks no region: \"us-east-1\")")
                    {:type :attic/bad-settings :errors {:region ["should be a region aws-api knows"]}}))))

(defn make-client
  "aws-api's S3 client from the settings."
  [{:keys [endpoint region access-key secret-key] {:keys [max-retries first-wait-ms max-wait-ms]} :retries :as settings}]
  (check-region region)
  (aws/client {:api                  :s3
               :region               region
               :endpoint-override    (turn-endpoint-into-parts endpoint)
               :credentials-provider (credentials/basic-credentials-provider
                                      {:access-key-id (safe/reveal access-key) :secret-access-key (safe/reveal secret-key)})
               :http-client          (make-http-client settings)
               :retriable?           is-retriable?
               :backoff              (retry/capped-exponential-backoff first-wait-ms max-wait-ms max-retries)}))

(defn- find-failure
  "The anomaly's category and the bucket's error code, when the answer is
   a failure; nil when it isn't."
  [answer]
  (when-let [category (:cognitect.anomalies/category answer)]
    {:category category
     :status   (:cognitect.aws.http/status answer)
     :code     (or (:cognitect.aws.error/code answer) (get-in answer [:Error :Code]))
     :message  (or (:cognitect.anomalies/message answer) (get-in answer [:Error :Message]))}))

(defn- throw-failure [what object-key failure]
  (throw (ex-info (str "The bucket couldn't " what " " object-key ": " (pr-str failure))
                  (assoc failure :type :attic/bucket-failed :object-key object-key))))

(defn put-object-once!
  "Puts the bytes at the key unless the bucket has an object there
   (If-None-Match: *, so an object is never written over). Returns :put,
   or :there-already; throws :attic/bucket-failed."
  [client bucket object-key ^bytes bs content-type]
  (let [answer (aws/invoke client {:op      :PutObject
                                   :request {:Bucket        bucket
                                             :Key           object-key
                                             :Body          bs
                                             :ContentLength (alength bs)
                                             :ContentType   content-type
                                             :IfNoneMatch   "*"}})]
    (if-let [failure (find-failure answer)]
      (if (= 412 (:status failure))
        :there-already
        (throw-failure "store" object-key failure))
      :put)))

(defn read-object-bytes
  "The object's bytes, or nil when the bucket has no object at the key;
   throws :attic/bucket-failed."
  [client bucket object-key]
  (let [answer (aws/invoke client {:op :GetObject :request {:Bucket bucket :Key object-key}})]
    (if-let [failure (find-failure answer)]
      (if (= :cognitect.anomalies/not-found (:category failure))
        nil
        (throw-failure "read" object-key failure))
      (with-open [in ^InputStream (:Body answer)]
        (.readAllBytes in)))))

(defn delete-object!
  "Deletes the object at the key; the bucket says the same whether it had
   one or not. Throws :attic/bucket-failed."
  [client bucket object-key]
  (let [answer (aws/invoke client {:op :DeleteObject :request {:Bucket bucket :Key object-key}})]
    (when-let [failure (find-failure answer)]
      (throw-failure "delete" object-key failure))
    :deleted))

(defn stop-client! [client]
  (aws/stop client))

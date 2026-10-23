(ns groundwork-file-storage.links
  "Links a browser reads a file through.

   A signed link: an S3 GET with SigV4's query-string signature, written
   here (aws-api signs requests, but makes no presigned links). Anyone
   holding it reads that one file until it expires; a private bucket stays
   private. Path-style, on the settings' :endpoint:
   <endpoint>/<bucket>/<key>?X-Amz-…&X-Amz-Signature=…

   A public link: the settings' :public-url and the key, for a bucket (or a
   CDN in front of it) every browser may read."
  (:require
   [clojure.string :as str]
   [groundwork-settings.api :as settings])
  (:import
   (java.net URI)
   (java.nio.charset StandardCharsets)
   (java.security MessageDigest)
   (java.time Instant ZoneOffset)
   (java.time.format DateTimeFormatter)
   (java.util Locale)
   (javax.crypto Mac)
   (javax.crypto.spec SecretKeySpec)))

(def ^:private ^DateTimeFormatter amz-date-format
  (.withZone (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'" Locale/US) ZoneOffset/UTC))

(defn- hmac-sha256 ^bytes [^bytes signing-key ^String data]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. signing-key "HmacSHA256"))
    (.doFinal mac (.getBytes data StandardCharsets/UTF_8))))

(defn- turn-bytes-into-hex [^bytes bs]
  (str/join (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn- hash-text [^String text]
  (turn-bytes-into-hex (.digest (MessageDigest/getInstance "SHA-256") (.getBytes text StandardCharsets/UTF_8))))

(defn- encode-for-uri
  "RFC 3986 percent-encoding of every byte but the unreserved ones, as
   SigV4 wants; / kept when keep-slash? (a path), else encoded (a query
   value)."
  [^String text keep-slash?]
  (str/join
   (for [b (.getBytes text StandardCharsets/UTF_8)
         :let [i (bit-and b 0xff)
               c (char i)]]
     (cond
       (or (<= (int \A) i (int \Z)) (<= (int \a) i (int \z)) (<= (int \0) i (int \9))
           (contains? #{\- \_ \. \~} c)) (str c)
       (and keep-slash? (= c \/))       "/"
       :else                            (format "%%%02X" i)))))

(defn- make-signing-key ^bytes [^String secret-key ^String day region]
  (-> (.getBytes (str "AWS4" secret-key) StandardCharsets/UTF_8)
      (hmac-sha256 day)
      (hmac-sha256 region)
      (hmac-sha256 "s3")
      (hmac-sha256 "aws4_request")))

(defn make-signed-link
  "A signed GET link to the object at the key, reading it for
   :link-expiry-seconds from now."
  [{:keys [endpoint bucket region access-key secret-key link-expiry-seconds]} object-key ^Instant now]
  (let [uri          (URI. endpoint)
        port         (.getPort uri)
        host         (if (neg? port) (.getHost uri) (str (.getHost uri) ":" port))
        amz-date     (.format amz-date-format now)
        day          (subs amz-date 0 8)
        scope        (str day "/" region "/s3/aws4_request")
        path         (str "/" (encode-for-uri bucket false) "/" (encode-for-uri object-key true))
        query        (->> {"X-Amz-Algorithm"     "AWS4-HMAC-SHA256"
                           "X-Amz-Credential"    (str (settings/reveal access-key) "/" scope)
                           "X-Amz-Date"          amz-date
                           "X-Amz-Expires"       (str link-expiry-seconds)
                           "X-Amz-SignedHeaders" "host"}
                          (map (fn [[k v]] [(encode-for-uri k false) (encode-for-uri v false)]))
                          (sort-by first)
                          (map (fn [[k v]] (str k "=" v)))
                          (str/join "&"))
        canonical    (str/join "\n" ["GET" path query (str "host:" host "\n") "host" "UNSIGNED-PAYLOAD"])
        to-sign      (str/join "\n" ["AWS4-HMAC-SHA256" amz-date scope (hash-text canonical)])
        signature    (turn-bytes-into-hex (hmac-sha256 (make-signing-key (settings/reveal secret-key) day region) to-sign))]
    (str (.getScheme uri) "://" host path "?" query "&X-Amz-Signature=" signature)))

(defn make-public-link
  "The public address of the object at the key; throws when the settings
   have no :public-url."
  [{:keys [public-url]} object-key]
  (when-not public-url
    (throw (ex-info "This file storage has no :public-url: its files are read through signed links (make-signed-link)"
                    {:type :groundwork-file-storage/no-public-url :object-key object-key})))
  (str public-url "/" (encode-for-uri object-key true)))

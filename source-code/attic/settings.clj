(ns attic.settings
  "attic's settings, and the options its functions take, as malli shapes,
   checked before anything runs. Nothing has a default: every key is
   written out (nil where it is a choice), and a missing or unknown one
   refuses, named, with every other problem at once. Checked by malli
   itself: they are values the app hands in, not data crossing an edge."
  (:require
   [attic.kinds :as kinds]
   [malli.core :as malli]
   [malli.error :as malli-error]
   [safe.api :as safe]))

(def ^:private seven-days-seconds
  "The longest a SigV4 presigned link may live: S3's limit, which R2 keeps."
  604800)

(def settings-shape
  "make-attic's settings, every key required.
   :endpoint: the S3 API's address, \"https://<account>.r2.cloudflarestorage.com\"
   or \"https://s3.eu-central-1.amazonaws.com\"; requests go path-style,
   <endpoint>/<bucket>/<key>.
   :bucket: the bucket every file goes into.
   :access-key, :secret-key: a key that may read and write that bucket;
   secrets of safe's (#secret :attic/access-key, #secret :attic/secret-key
   in the app's settings file), text written out refused.
   :region: the region requests and links are signed for; one the aws-api
   knows (\"us-east-1\", \"eu-central-1\"). R2 checks no region: write
   \"us-east-1\" (aws-api has no endpoint for R2's \"auto\").
   :public-url: where browsers read the bucket's files without a link,
   \"https://files.shop.example\" (a public bucket or a CDN in front of
   it), or nil when the bucket is private: make-public-link then throws.
   :link-expiry-seconds: how long a signed link reads its file, 1 to
   604800 (seven days, S3's longest).
   :connect-timeout-ms: how long a request waits for the connection.
   :request-timeout-ms: how long a request waits for its whole answer,
   after the connection.
   :retries: a request the bucket was too busy for, or that never
   connected, is sent again, at most :max-retries more times, waiting
   :first-wait-ms, then twice as long each time, never longer than
   :max-wait-ms."
  [:map {:closed true}
   [:endpoint [:re {:error/message "should be an address starting with http:// or https://"} #"^https?://[^/]+$"]]
   [:bucket [:string {:min 1}]]
   [:access-key safe/secret]
   [:secret-key safe/secret]
   [:region [:string {:min 1}]]
   [:public-url [:maybe [:re {:error/message "should be an address starting with http:// or https://, no / at the end"} #"^https?://.*[^/]$"]]]
   [:link-expiry-seconds [:int {:min 1 :max seven-days-seconds}]]
   [:connect-timeout-ms [:int {:min 1}]]
   [:request-timeout-ms [:int {:min 1}]]
   [:retries [:map {:closed true}
              [:max-retries [:int {:min 0}]]
              [:first-wait-ms [:int {:min 0}]]
              [:max-wait-ms [:int {:min 0}]]]]])

(def log-entry
  "The app's log function, (log level event data), made by logbook."
  [:log [fn? {:error/message "should be the app's log function, made by logbook"}]])

(def meter-entry
  "The app's meter, made by meter's make-meter: make-attic declares
   attic's metrics on it. A value make-meter didn't make is refused by
   meter (:meter/not-a-meter)."
  [:meter [some? {:error/message "should be the app's meter, made by meter.api/make-meter"}]])

(def options-shape
  "make-attic's options: the app's log and meter."
  [:map {:closed true} log-entry meter-entry])

(def key-prefix-shape
  "The folder a file's key starts with, \"orders/photos\": segments of
   letters, digits, . _ and -, joined by /, no . or .. segment, no / at
   either end."
  [:and
   [:re {:error/message "should be segments of letters, digits, . _ and -, joined by /"}
    #"^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$"]
   [:fn {:error/message "should have no . or .. segment"}
    #(not (re-find #"(^|/)\.\.?(/|$)" %))]])

(def content-types-shape
  "A set of content types attic tells by their bytes (attic.kinds)."
  [:set {:min 1} (into [:enum] (sort kinds/known-content-types))])

(def store-options-shape
  "store-file!'s options, every key required.
   :key-prefix: the folder the key starts with (key-prefix-shape).
   :allowed-content-types: what the bytes may be, a set of
   known-content-types; anything else is refused, :attic/wrong-kind.
   :file-name: the file's name, or nil; read only to tell a .gltf (JSON)
   from other JSON."
  [:map {:closed true}
   [:key-prefix key-prefix-shape]
   [:allowed-content-types content-types-shape]
   [:file-name [:maybe :string]]])

(def upload-settings-shape
  "make-upload-handler's settings, every key required.
   :key-prefix: the folder every uploaded file's key starts with.
   :allowed-content-types: what an uploaded file's bytes may be.
   :max-file-bytes: the largest file taken; a larger one is refused, the
   others stored. The server's own body limit (bridge's
   :max-body-bytes) must leave room for it.
   :max-files: the most parts one request may carry; more refuse the
   request, 413, nothing stored."
  [:map {:closed true}
   [:key-prefix key-prefix-shape]
   [:allowed-content-types content-types-shape]
   [:max-file-bytes [:int {:min 1}]]
   [:max-files [:int {:min 1}]]])

(def upload-options-shape
  "make-upload-handler's options, every key required.
   :pool: the app's pool, the files' rows written on it.
   :wire: the app's wire, the answer written as JSON by it.
   :may-upload?: (fn [request] true-or-false), asked before the body is
   read: who may store files is the app's to say."
  [:map {:closed true}
   [:pool some?]
   [:wire [some? {:error/message "should be the app's wire, made by wire.api/make-wire-from-settings"}]]
   [:may-upload? fn?]])

(def list-options-shape
  "list-files' options, every key required.
   :key-prefix: only the keys that start with it, \"\" for every key.
   :after-key: only the keys after it, nil to start at the first.
   :limit: at most this many."
  [:map {:closed true}
   [:key-prefix :string]
   [:after-key [:maybe :string]]
   [:limit [:int {:min 1}]]])

(def delete-unnamed-options-shape
  "delete-unnamed-files!' options, every key required.
   :key-prefix: only the keys that start with it, \"\" for every key.
   :page-size: how many rows are read at a time."
  [:map {:closed true}
   [:key-prefix :string]
   [:page-size [:int {:min 1}]]])

(defn check-data-for-shape
  "Returns the data; throws {:type kind :errors …} with every problem at
   once when it doesn't fit the shape."
  [what kind shape data]
  (when-let [explanation (malli/explain shape data)]
    (let [errors (malli-error/humanize explanation)]
      (throw (ex-info (str what " have problems: " (pr-str errors))
                      {:type kind :errors errors}))))
  data)

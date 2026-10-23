(ns attic.metrics
  "attic's metrics, declared on the app's meter by make-attic:

     attic_files_stored_total{outcome}        counter: files stored, new or there already
     attic_files_refused_total{reason}        counter: files refused, by why
     attic_files_deleted_total{outcome}       counter: files asked to be deleted, by what happened
     attic_bucket_failures_total{operation}   counter: requests the bucket failed, after retries"
  (:require
   [meter.api :as meter]))

(def outcomes
  "How a store ended:
   :stored          the bucket had no object at its key; it is put
   :already-stored  the same bytes were stored before: nothing put"
  #{:stored :already-stored})

(def reasons
  "Why a file was refused:
   :wrong-kind  its bytes are no content type the call allows
   :too-large   an upload's file larger than :max-file-bytes"
  #{:wrong-kind :too-large})

(def deletions
  "How a delete ended:
   :deleted      the row and the object are gone
   :still-named  a row of the app names the file (a reference to file__key): nothing deleted
   :not-found    no file has that key"
  #{:deleted :still-named :not-found})

(def operations #{:store :read :delete})

(defn declare-metrics! [meter]
  (meter/declare-metrics!
   meter
   [{:name    :attic/files-stored-total
     :type    :counter
     :help    "Files stored, by whether the bucket had them already (the same bytes are one file)."
     :labels  {:outcome outcomes}
     :buckets nil
     :read    nil}
    {:name    :attic/files-refused-total
     :type    :counter
     :help    "Files refused, by why: their bytes are no kind allowed, or an upload's file is too large."
     :labels  {:reason reasons}
     :buckets nil
     :read    nil}
    {:name    :attic/files-deleted-total
     :type    :counter
     :help    "Files asked to be deleted, by what happened: deleted, kept since a row still names them, or no such file."
     :labels  {:outcome deletions}
     :buckets nil
     :read    nil}
    {:name    :attic/bucket-failures-total
     :type    :counter
     :help    "Requests the bucket failed, after their retries, by operation."
     :labels  {:operation operations}
     :buckets nil
     :read    nil}]))

(defn count-stored! [meter outcome]
  (meter/add-to-counter! meter :attic/files-stored-total {:outcome outcome} 1))

(defn count-refused! [meter reason]
  (meter/add-to-counter! meter :attic/files-refused-total {:reason reason} 1))

(defn count-deleted! [meter outcome]
  (meter/add-to-counter! meter :attic/files-deleted-total {:outcome outcome} 1))

(defn count-bucket-failure! [meter operation]
  (meter/add-to-counter! meter :attic/bucket-failures-total {:operation operation} 1))

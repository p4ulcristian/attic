(ns groundwork-file-storage.api-names-test
  "The public names of groundwork-file-storage.api. Adding or removing one fails here, so
   the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork-file-storage.api]))

(deftest the-api-names
  (is (= '#{settings-shape
           upload-settings-shape
           known-content-types
           files-table
           make-file-storage
           stop-file-storage!
           store-file!
           find-file
           list-files
           read-file-bytes
           delete-file!
           delete-file-after-commit!
           delete-unnamed-files!
           make-signed-link
           make-public-link
           make-upload-handler}
         (set (keys (ns-publics 'groundwork-file-storage.api))))))

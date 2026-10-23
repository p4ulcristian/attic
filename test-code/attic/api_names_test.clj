(ns attic.api-names-test
  "The public names of attic.api. Adding or removing one fails here, so
   the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [attic.api]))

(deftest the-api-names
  (is (= '#{settings-shape
           upload-settings-shape
           known-content-types
           files-table
           make-attic
           stop-attic!
           store-file!
           find-file
           list-files
           read-file-bytes
           delete-file!
           delete-unnamed-files!
           make-signed-link
           make-public-link
           make-upload-handler}
         (set (keys (ns-publics 'attic.api))))))

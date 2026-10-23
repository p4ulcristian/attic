(ns attic.kinds
  "What kind of file some bytes are, from the bytes, never from what the
   browser says: the first bytes every format of its kind starts with.
   Markup (SVG, HTML) is no kind attic knows: a file a browser could run
   is never stored to be shown."
  (:require
   [clojure.string :as str])
  (:import
   (java.nio.charset StandardCharsets)
   (java.util Arrays)))

(def extensions-of-content-types
  "Every content type attic tells by its bytes, and the extension its
   files' keys end with."
  {"image/png"          "png"
   "image/jpeg"         "jpg"
   "image/gif"          "gif"
   "image/webp"         "webp"
   "image/avif"         "avif"
   "image/vnd.radiance" "hdr"
   "application/pdf"    "pdf"
   "model/gltf-binary"  "glb"
   "model/gltf+json"    "gltf"})

(def known-content-types
  (set (keys extensions-of-content-types)))

(defn- does-start-with? [^bytes bs offset signature]
  (and (>= (alength bs) (+ offset (count signature)))
       (every? true? (map-indexed (fn [i b] (= (unchecked-byte b) (aget bs (+ offset i)))) signature))))

(defn- turn-head-into-text
  "The first kilobyte as lower-case text, a byte-order mark and leading
   space left out."
  [^bytes bs]
  (-> (String. (Arrays/copyOf bs (int (min 1024 (alength bs)))) StandardCharsets/UTF_8)
      (str/replace "﻿" "")
      str/triml
      str/lower-case))

(defn- find-extension [file-name]
  (some-> file-name (str/split #"\.") last str/lower-case))

(defn find-content-type
  "The content type of the bytes, one of known-content-types, or nil for
   anything else. The file's name (or nil) only tells a .gltf, which is
   JSON, from other JSON."
  [^bytes bs file-name]
  (let [text (turn-head-into-text bs)]
    (cond
      (does-start-with? bs 0 [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A])                   "image/png"
      (does-start-with? bs 0 [0xFF 0xD8 0xFF])                                            "image/jpeg"
      (does-start-with? bs 0 (map int "GIF8"))                                            "image/gif"
      (and (does-start-with? bs 0 (map int "RIFF")) (does-start-with? bs 8 (map int "WEBP"))) "image/webp"
      (and (does-start-with? bs 4 (map int "ftyp")) (does-start-with? bs 8 (map int "avif"))) "image/avif"
      (does-start-with? bs 0 (map int "glTF"))                                            "model/gltf-binary"
      (does-start-with? bs 0 (map int "%PDF-"))                                           "application/pdf"
      (or (str/starts-with? text "#?radiance") (str/starts-with? text "#?rgbe"))          "image/vnd.radiance"
      (and (= "gltf" (find-extension file-name)) (str/starts-with? text "{"))             "model/gltf+json"
      :else                                                                               nil)))

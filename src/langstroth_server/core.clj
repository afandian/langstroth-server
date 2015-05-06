(ns langstroth-server.core
  (:require [org.httpkit.server :as server])
  (:require [liberator.core :refer [defresource]]
            [liberator.representation :refer [ring-response]])
  (:require [compojure.core :refer [defroutes ANY GET POST]]
            [compojure.route :as route])
  (:import (java.io File InputStream FileInputStream))
  (:require [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.basic-authentication :refer [wrap-basic-authentication]])
  (:require [clojure.core.async :refer [chan >! >!! <! <!! go]])
  (:gen-class))

(def config {:port 6060 :storage-dir "/Users/joe/personal/langstroth-storage"})

(def cred {:username "joe" :password "joe"})

(def process-queue (chan 1000))

(def max-slice-count 121)


(defn shell [args]
  (prn "Shell" args)
  (let [s (.exec (Runtime/getRuntime) (into-array String args))]
      (.waitFor s)))

(defn process-file
  "Convert the file to MP3"
  [f]
  (let [wav-filename (.getAbsolutePath f)
        output-filename (str (.substring wav-filename 0 (- (.length wav-filename) 4)) ".short.wav")]
    (prn "Convert " wav-filename)
    ; Trim one second's worth. 
    ; Microphone input seems to be doing some auto-calibrating in the first second.
    (shell ["sox" wav-filename output-filename "trim" "3" "1"])
    (let [output-file (new File output-filename)]
      (when (.exists output-file)
        (.delete f)
        ; Rename back to original filename.
        (.renameTo output-file (new File wav-filename))
        ))))
  
(defn start-process-queue []
  (go
    (prn "Process queue...")
    (loop [f (<! process-queue)]
      (prn "Process queue tick")
        (when f
          (do 
            (process-file f)
            (recur (<! process-queue)))))))

(defn parse-int [input]
  (try 
    (new BigInteger input)
  (catch NumberFormatException _ nil)))

(defn abs [n] (max n (- n)))



(defn authorized-handler
  "Return user id if logged in."
  [ctx]
  (when-let [user-id (-> ctx :request :session :user-id)]
    {::user-id user-id}))

(defresource recordings
  []
  :allowed-methods [:get])

(defresource recording
  [user entity duration filename]
  :allowed-methods [:put :get]
  :available-media-types ["audio/wav"]
  :authorized? authorized-handler
  :exists? true
  :exists? (fn [ctx]
             (let [filename (if (.endsWith filename ".wav") (.substring filename 0 (- (.length filename) 4)) filename)
                   f (new File (new File (:storage-dir config)) (str "recordings/" user "/" entity "/" filename ".wav"))
                   exists (.exists f)]
               [exists {::file f}]))
  
  :handle-ok (fn [ctx] (let [f (::file ctx)]
               (new FileInputStream f)))

  :put! (fn [ctx]
            (let [f (::file ctx)]
              (.mkdirs (.getParentFile f))
              (prn (str "Uploading " f))
              (with-open [is (clojure.java.io/input-stream (get-in ctx [:request :body]))]
                (with-open [os (clojure.java.io/output-stream f)]
                  (clojure.java.io/copy is os)))
              (prn "Put on process queue" f)
              (>!! process-queue f)
              true)))

(defn nearest
  "For list of files with timestamp filenames, find the nearest."
  [sought items]
  (let [with-distances (map (fn [[timestamp f]]
                              [(abs (- sought timestamp)) [timestamp f]]) items)
        best (first (sort-by first with-distances))]
    (second best)))

(defn generate-splice
  "Generate a spliced file and return a File."
  [user entity start end skip]
  (let [output (new File (new File (:storage-dir config)) (str "/timelapse/" user "/" entity "/" start "-" end "-" skip ".mp3"))]
    (if (.exists output)
      output
      (let [files (.listFiles (new File (new File (:storage-dir config)) (str "recordings/" user "/" entity)))
            files (filter #(.endsWith (.getName %) ".wav") files)
            filenames (map (fn [f]
                                      (let [nom (.getName f)
                                            timestamp-str (.substring nom 0 (- (.length nom) 4))
                                            timestamp (parse-int timestamp-str)]
                                        [timestamp f])) files)
            interested (filter #(and (>= (first %) start) (<= (first %) end)) filenames)]
        (when (not (empty? interested))
          (let [time-range (range start end skip)
               
                nearest (map #(nearest % interested) time-range)
                paths (map (fn [[_ file]] (.getAbsolutePath file)) nearest)
                
                ; TODO could use ffmpeg and use an unlimited number of input files
                concat-command (concat ["sox" "--combine" "concatenate"] paths [(str (.getAbsolutePath output))])]

            (.mkdirs (.getParentFile output))
            (shell concat-command)
            output))))))

(defn generate-spectrogram
  "Generate spectrogram from mp3"
  [user entity start end skip input-f]
  (let [output (new File (new File (:storage-dir config)) (str "spectrograms/" user "/" entity "/" start "-" end "-" skip ".png"))]
    (if (.exists output)
      output
      (let [duration-millis (- end start)
            duration-minutes (/ duration-millis (* 1000 60))
            units (cond
                    ; Less than 1 hr
                    (< duration-minutes 60) :minutes
                    ; Less than 24 hr
                    (< duration-minutes (* 60 24)) :hours
                    :default :days)
            title (str "Langstroth - " (condp = units
                                         :minutes (str duration-minutes " minutes")
                                         :hours (str (int (/ duration-minutes 60)) " hours")
                                         :days (str (int (/ duration-minutes (* 60 24))) " days")))
            
            spectrogram-command ["sox" (str (.getAbsolutePath input-f)) "-n" "spectrogram" "-l" "-t" title "-o" (str (.getAbsolutePath output))]]
    (.mkdirs (.getParentFile output))
    (shell spectrogram-command)
    output))))


(defresource timelapse-recording
  [user entity]
  ; TODO user exists
  :available-media-types ["audio/mp3"]
  :malformed? (fn [ctx] 
                (let [start (parse-int (get-in ctx [:request :params "start"]))
                      end (parse-int (get-in ctx [:request :params "end"]))
                      skip (parse-int (get-in ctx [:request :params "skip"] ))
                      ; If this requires more than this many files, don't do it.
                      ; Can be solved by upping the skip.
                      acceptable-slice-count (< (/ (- end start) skip) max-slice-count)]
                          (prn (float (/ (- end start) skip)))
                          [(not (and start end skip acceptable-slice-count (> end start) ))
                           {::start start ::end end ::skip skip}]))
  :exists? (fn [ctx] (let [start (::start ctx)
                             end (::end ctx)
                             skip (::skip ctx)
                             output-f (generate-splice user entity start end skip)]
                       ; May return nil if nothing matched.
                       [output-f {::output-f output-f}]))
  :handle-ok (fn [ctx] (new FileInputStream (::output-f ctx))))

(defresource timelapse-spectrogram
  [user entity]
  ; TODO user exists
  :available-media-types ["image/png"]
  :malformed? (fn [ctx] 
                (let [start (parse-int (get-in ctx [:request :params "start"]))
                      end (parse-int (get-in ctx [:request :params "end"]))
                      skip (parse-int (get-in ctx [:request :params "skip"] ))
                      ; If this requires more than this many files, don't do it.
                      ; Can be solved by upping the skip.
                      acceptable-slice-count (< (/ (- end start) skip) max-slice-count)]

                  (prn (float (/ (- end start) skip)))
                      
                  [(not (and start end skip acceptable-slice-count (> end start)))
                   {::start start ::end end ::skip skip}]))
  :exists? (fn [ctx] (let [start (::start ctx)
                             end (::end ctx)
                             skip (::skip ctx)
                             mp3-f (generate-splice user entity start end skip)
                             spectrogram-f (when mp3-f (generate-spectrogram user entity start end skip mp3-f))]
            ; May return nil if nothing matched.
                       [spectrogram-f {::spectrogram-f spectrogram-f}]))
  :handle-ok (fn [ctx] (new FileInputStream (::spectrogram-f ctx))))

  (defn authenticated? [username password]
  (when (and 
          (= (:username cred) username)
          (= (:password cred) password))
    "JOE"))

; Perform login with Basic Authentication and set cookie.
(defresource login
  [auth]
  :allowed-methods [:post :get]
  :available-media-types ["text/plain", "text/html"]
  :accepts ["application/json"]
  :handle-ok (fn [ctx]
               (ring-response {:headers {"Content-Type" "text/plain"
                                         "User-Id" (:basic-authentication auth)}
                               ; :basic-authentication is the response of `authenticated?`
                               :session {:user-id (:basic-authentication auth)}
                                :body "ok"})))

; Simple handler to check if the session is authenticated.
(defresource authenticated
  []
  :available-media-types ["text/plain", "text/html"]
  :allowed-methods [:get :head]
  :authorized? authorized-handler
  :handle-ok (fn [ctx]
               (ring-response {:headers {"Content-Type" "text/plain"
                                         "User-Id" (::user-id ctx)}                               
                                :body "ok"})))

(defresource recordings-info
  [user entity]
  :available-media-types ["application/json"]
  :handle-ok (fn [ctx]
              (let [files (.listFiles (new File (new File (:storage-dir config)) (str "recordings/" user "/" entity)))
                    files (filter #(.endsWith (.getName %) ".wav") files)
                    timestamps (map (fn [f]
                                     (let [nom (.getName f)
                                           timestamp-str (.substring nom 0 (- (.length nom) 4))
                                           timestamp (parse-int timestamp-str)]
                                        timestamp)) files)
                    earliest (apply min timestamps)
                    latest (apply max timestamps)]
                {:earliest earliest :latest latest :max-slice-count max-slice-count})))

(defroutes app-routes
  (ANY "/login" [] (wrap-basic-authentication login authenticated?))
  (ANY "/authenticated" [] (authenticated))
  (ANY "/recordings/:user/:entity/:duration/:filename" [user entity duration filename] (recording user entity duration filename))
  (ANY "/recordings/:user/:entity/timelapse" [user entity] (timelapse-recording user entity))
  (ANY "/recordings/:user/:entity" [user entity] (recordings-info user entity))
  (ANY "/spectrograms/:user/:entity/timelapse" [user entity] (timelapse-spectrogram user entity))
  (ANY "/recordings" recordings)
  (route/resources "/" {:root "public/index.html"})
  (route/resources "/" {:root "public"}))

(def app
  (-> app-routes
    (wrap-params)
    (wrap-session {:store (cookie-store {:key "TODOTODOTODOTODO"})})))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!"))


(defonce s (atom nil))


(defn stop-server
  []
  (@s)
  (reset! s nil)
  (prn "Stop Server" @s))

(defn start-server []
  (start-process-queue)
  (reset! s (server/run-server app {:port (:port config)}))
  (prn "Start Server" @s config))

(defn restart-server []
  (stop-server)
  (start-server))

(defn -main
  [& args]
  (prn "Config" config)
  (server/run-server app {:port (:port config)}))


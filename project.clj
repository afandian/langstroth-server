(defproject langstroth-server "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [liberator "0.12.2"]
                 [compojure "1.2.0"]
                 [http-kit "2.1.16"]
                 [lein-ring "0.8.13"]
                 [ring "1.3.2"]
                 [org.clojure/data.json "0.2.5"]
                 [ring-basic-authentication "1.0.5"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 ]
  :main ^:skip-aot langstroth-server.core
  :target-path "target/%s"
  :ring {:handler langstroth-server.core/app}
  :profiles {:uberjar {:aot :all}})

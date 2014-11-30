(defproject microblog "0.1.0-SNAPSHOT"
  :description "a simple microblog on ring-jetty"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [ring "1.3.1"]
                 [compojure "1.2.1"]
                 [com.taoensso/carmine "2.7.0" :exclusions [org.clojure/clojure]]
                 [ring-mock "0.1.5"]
                 [selmer "0.7.5"]]
  :ring {:handler microblog.core/app
         :auto-reload? true
         :auto-refresh? true
         :nrepl {:start? true
                 :port 9998}})

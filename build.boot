(def project 'slim-coffee)
(def version "0.1.0-SNAPSHOT")

(set-env! :source-paths #{"src/cljs" "src/cljc" "src/clj"}
          :resource-paths #{"resources"}
          :dependencies   '[[adzerk/boot-cljs "1.7.228-2"]
                            [adzerk/boot-reload "0.4.12"]
                            [org.clojure/clojure "1.9.0-alpha14"]
                            [org.clojure/clojurescript  "1.9.495"]
                            [org.clojure/core.async "0.3.443"]
                            [mount "0.1.11"]
                            [bidi "2.1.1"]
                            [http-kit "2.3.0-alpha2"]
                            [com.cognitect/transit-clj "0.8.300"]])

(task-options!
 aot {:namespace   #{'slim-coffee.core}}
 pom {:project     project
      :version     version
      :description "FIXME: write description"
      :url         "http://example/FIXME"
      :scm         {:url "https://github.com/yourname/slim-coffee"}
      :license     {"Eclipse Public License"
                    "http://www.eclipse.org/legal/epl-v10.html"}}
 jar {:main        'slim-coffee.core
      :file        (str "slim-coffee-" version "-standalone.jar")})

(require
  '[adzerk.boot-cljs :refer [cljs]]
  '[adzerk.boot-reload :refer [reload]])

(deftask build
  "Build the project locally as a JAR."
  [d dir PATH #{str} "the set of directories to write to (target)."]
  (comp
   (cljs :optimizations :advanced)
   (aot)
   (pom)
   (uber)
   (jar)
   (sift)
   (target :dir dir)))

(deftask dev
  "Run the project."
  [a args ARG [str] "the arguments for the application."]
  (require 'slim-coffee.core)
  (comp
   (watch)
   (reload :asset-path "public")
   (cljs)
   (target)
   (with-pass-thru _
     (apply (resolve 'slim-coffee.core/dev-main) args))))

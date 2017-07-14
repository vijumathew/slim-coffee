(def project 'slim-coffee)
(def version "0.1.0-SNAPSHOT")

(set-env! :resource-paths #{"src"}
          :dependencies   '[[org.clojure/clojure "1.9.0-alpha14"]
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

(deftask build
  "Build the project locally as a JAR."
  [d dir PATH #{str} "the set of directories to write to (target)."]
  (let [dir (if (seq dir) dir #{"target"})]
    (comp (aot) (pom) (uber) (jar) (target :dir dir))))

(deftask run
  "Run the project."
  [a args ARG [str] "the arguments for the application."]
  (require '[slim-coffee.core :as app])
  (apply (resolve 'app/-main) args))


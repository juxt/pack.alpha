(ns juxt.pack.cli.api
  (:require
    [clojure.tools.deps.alpha :as deps]
    [juxt.pack.api :as pack]))

(defn create-basis
  "Like deps/create-basis, but excludes :user deps.edn"
  [{:keys [basis] :as params}]
  (deps/create-basis (merge {:user nil} params)))

(defn docker
  "Build a docker image via jib.

  Libs are in a separate layer for efficiency.  Classpath is kept in the same
  form (e.g. gitlibs/:paths are directories).

  :jvm-opts and :main-opts are used from :basis, so use :aliases to set it.
  
  Options:
    :basis - basis to use, if not provided will create a basis without :user
    :image-name - required, name of the docker image
    :image-type - required, keyword of type of image to produce
    :tar-file - tar file to create
    :include - Not yet implemented
    :env - map of environment variables to set

    Runtime:
    :base-image - base docker image to use, default = gcr.io/distroless/java:11
    :user - user to run the container as, string in format of user, uid, :group,
            :gid, user:group, uid:gid, uid:group, user:gid

    Credentials
    :from-registry - map of :username and :password to string, used for pulling from registry
    :to-registry  - map of :username and :password to string, used for uploading to registry

    Metadata:
    :tags - set of additional tags to apply to this image, default = #{}
    :labels - map of string to string of object labels, default = {}

    Repeatability:
    :creation-time - java.time.Instant to set creation time of image to, default = (Instant/now)
   
  Image types:
    :docker - Upload to docker daemon
    :registry - Upload to a registry, uses :to-registry if set,
                otherwise ~/.docker/config.json
    :tar - Write a tar file, requires :tar-file to be set"
  [{:keys [basis] :as params}]
  (pack/docker (assoc params :basis (or basis (create-basis nil)))))

(defn skinny
  "Output jars or directories from paths and libs.

  Options
    :basis - basis to use, if not provided will create a basis without :user
    :libs - folder to put libs in, if not supplied libs aren't output,
            default = nil
    :lib-coerce - nil or :jar.  :jar will convert libs to jar files if they are
                  directories, default = nil
    :path - location to output :paths to, if not supplied :paths aren't output,
            default = nil
    :path-coerce - nil or :jar. :jar will output a jar file to :path instead of
                   a directory, default = nil"
  [{:keys [basis] :as params}]
  (pack/skinny (assoc params :basis (or basis (create-basis nil)))))

(defn library
  "Produce a library from :paths in a basis
  
  Options
    :basis - basis to use, if not provided will create a basis without :user
    :path - required, location to output library to
    :pom - input pom.xml, if provided will be copied to
           META-INF/maven/<group>/<artifact>/pom.xml
    :lib - required if :pom supplied, used to create pom.xml path"
  [{:keys [basis] :as params}]
  (pack/library (assoc params :basis (or basis (create-basis nil)))))

(defn aws-lambda
  "Produce a zip file that can be uploaded to AWS lambda.  You will need to AOT
  prior to building this lambda zip, and then include your aot alias in the
  basis.
  
  libs are converted into jars if they are directories. paths are put straight
  into the zip file.
  
  Options
    :basis - basis to use, if not provided will create a basis without :user
    :lambda-file - required, lambda file to create"
  [{:keys [basis] :as params}]
  (pack/aws-lambda (assoc params :basis (or basis (create-basis nil)))))

(defn one-jar
  "Produce a self-executable jar, using One-Jar.  This is an alternative to an
  uberjar, that doesn't require any conflict resolution for duplicate files.

  :main-opts from basis will be used as default arguments to the jar.  Create
  an alias with `{:main-opts [\"-m\" \"my.cool.ns\"]}` to specify a startup
  namespace.
  
  Caveats
    - The version of One-Jar is patched to fix miscellaneous bugs
    - This may be replaced with Uno-Jar in the future
    - Classpath scanning mechanisms don't work with One-Jar, as it uses a
      custom URL scheme.

  Options
    :basis - basis to use, if not provided will create a basis without :user
    :jar-file - jar file to create
    :main-class - main class to run jar with default = clojure.main, changing
                  this requires the use of aot and is considered more advanced.
                  Prefer setting :main-opts in your basis and using
                  clojure.main to load your main."
  [{:keys [basis] :as params}]
  (pack/one-jar (assoc params :basis (or basis (create-basis nil)))))

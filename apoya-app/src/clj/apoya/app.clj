(ns apoya.app
  (:use compojure.core
        fleet)
  (:require [apoya.config :as cfg]
            [apoya.i18n :as i18n]
            [apoya.errors :as errors]
            [apoya.resources.fs :as fs]
            [apoya.data.site :as site]
            [compojure.route :as route]
            [pantomime.mime :refer [mime-type-of]]
            [noir.util.middleware :as middleware]
            [ring.util.response :as response]
            [ring.middleware.gzip :as gzip]
            [cemerick.friend :as friend]
            (cemerick.friend [workflows :as workflows]
                             [credentials :as creds])
            [clojure.tools.logging :as log]
            [clojure.string :as s]))

(def users {"root" {:username "root"
                    :password (creds/hash-bcrypt "admin_password")
                    :roles #{::admin}}
            "jane" {:username "jane"
                    :password (creds/hash-bcrypt "user_password")
                    :roles #{::user}}})

(defn fleet-resource [{:keys [uri]}]
  (when (.endsWith uri ".html")
    (let [uri (s/replace uri #"\.html$" ".fleet")
          template (and (fs/site-blob-exists? uri)
                        (-> (fs/get-site-blob uri)
                            (.getPayload)
                            (.getInput)
                            (slurp)))]
      (when template
        (let [compile-fn (fleet [label] template {:escaping :xml})]
          {:body (.toString (compile-fn i18n/label))})))))

(defn jclouds-resource [{:keys [uri headers]}]
  (when (and (not (.endsWith uri "/"))
             (fs/site-blob-exists? uri))
    (let [mime-type (mime-type-of uri)
          if-none-match (get headers "if-none-match")
          res-blob (fs/get-site-blob uri)
          etag (-> res-blob
                   (.getMetadata)
                   (.getETag))
          body (-> res-blob
                   (.getPayload)
                   (.getInput))]
      (if (or (nil? etag) (not= etag if-none-match))
        (response/content-type {:body body
                                :headers {"etag" etag}
                                :status 200}
                               mime-type)
        {:status 304 :body "" :headers {"etag" etag}}))))

(defn handle-errors [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (errors/webapp-error request e)
        {:status 500
         :body "Error"}))))

(defn language-chooser [handler]
  (fn [request]
    (let [selected-language (i18n/find-supported-language request)]
      (binding [cfg/*language* selected-language]
        (handler request)))))

(defn site-chooser [handler]
  (fn [request]
    (let [prospected-site (keyword (get-in request [:headers "host"]))
          correct-site (if (site/site-exists? prospected-site)
                         prospected-site
                         :default)]
      (binding [cfg/*current-site* correct-site]
        (handler request)))))

(defroutes app-routes
  jclouds-resource
  fleet-resource
  (GET "/" [] (fleet-resource {:uri "/index.html"}))
  (GET "/hola" [] (/ 1 0))
  (POST "/login" [])
  (POST "/upload" request
        (println request))
  (GET "/adios" [] (friend/authorize #{::admin}
                                     "Admin page"))
  (route/not-found (fn [_]
                     (fleet-resource {:uri "/404.html"}))))

(def secured-routes
  (-> app-routes
      (friend/authenticate {:credential-fn (partial creds/bcrypt-credential-fn users)
                            :workflows [(workflows/interactive-form)]})))

(def app (middleware/app-handler
           [secured-routes]
           :middleware [handle-errors
                        language-chooser
                        site-chooser
                        gzip/wrap-gzip]
           :access-rules []
           :formats [:edn]))

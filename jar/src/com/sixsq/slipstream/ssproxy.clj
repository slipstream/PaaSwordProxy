(ns com.sixsq.slipstream.ssproxy
  "SlipStream Proxy for PaaSword project."
  (:require
    [clojure.string :as s]
    [clojure.tools.logging :as log]
    [clojure.data.json :as json]

    [compojure.core :refer :all]
    [compojure.route :as route]
    [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
    [ring.middleware.json :refer [wrap-json-body]]

    [sixsq.slipstream.client.api.lib.run :as ssr]
    [sixsq.slipstream.client.api.lib.app :as ssapp]
    [sixsq.slipstream.client.api.authn :as ssauth]
    [sixsq.slipstream.client.api.utils.http-sync :as http]
    [clojure.core.async :refer [alts!! >! go chan]]
    ))

; silence kvlt logs.
(require '[taoensso.timbre :as tlog])
(tlog/merge-config! {:ns-blacklist ["kvlt.*"]})

(def endpoint "https://nuv.la")
(def required-main-keys ["username" "password" "hookURL"])
(def required-comp-keys ["component" "location"])

(defn status-202
  [& [msg]]
  {:status  202
   :headers {"Content-Type" "text/plain"}
   :body    (str (or msg "Accepted."))})

(defn status-404
  [msg]
  {:status  404
   :headers {"Content-Type" "application/json"}
   :body    (json/write-str
              {:status  404
               :message msg})})

(defn assert-contians
  [m required-keys]
  (doseq [k required-keys]
    (if-not (contains? m k)
      (throw (Exception. (str "Missing key: " k ". Required keys: "
                              (clojure.string/join ", " required-keys)))))))

(defn components
  "Extracts components from request."
  [req]
  (apply (partial dissoc req) required-main-keys))

(defn validate-req
  "Validates request. Throws on missing info."
  [req]
  ; validate main part
  (assert-contians req required-main-keys)
  ; validate components
  (let [cmps (components req)]
    (if (empty? cmps)
      (throw (Exception. "Missing components to deploy.")))
    (doseq [[cn c] cmps]
      (try
        (assert-contians c required-comp-keys)
        (catch Exception e
          (throw (Exception. (format "On comp: %s. %s" cn (.getMessage e))))))))
  req)

(defn parse-request
  "Parses and validates request."
  [request]
  (-> request
      :body
      validate-req))

(defn user
  [req]
  (get req "username"))

(defn pass
  [req]
  (get req "password"))

(defn authn
  "Authenticates and returns cookie."
  [user pass]
  (ssauth/login user pass (ssauth/to-login-url endpoint)))

(defn creds
  "Extracts creds, authns and returns token-based creds for successive use with the service.
  Throws, if authentication falied to obtain cookie."
  [req]
  (if-let [cookie (authn (user req) (pass req))]
    {:cookie cookie}
    (throw (Exception. (str "Failed to authenticate to " endpoint))))
  )

(defn build-context
  [req]
  (merge {:serviceurl endpoint} (creds req)))

(defn comp-uri
  [c]
  (get c "component"))

(defn hook-url
  [req]
  (get req "hookURL"))

(defn comp-params
  [c]
  (apply (partial dissoc c) required-comp-keys))

(defn url-resource
  [url]
  (-> url
      (s/split #"/")
      last))

(defn deploy-comp
  "Returns run url."
  [c]
  (ssapp/deploy-comp (comp-uri c) (comp-params c)))

(defn put-json-result
  [url json-body]
  (http/put url {:content-type "application/json"
                 :body         (json/write-str json-body)
                 :insecure?    true}))

(defn put-json-failure
  [url error-msg]
  (put-json-result url {:status  409
                        :message (str "Failed provisioning resources with: " error-msg)}))

(defn collect
  [ports]
  (for [_ (range (count ports))]
    (let [[v p] (alts!! ports)]
      (log/debug (format "Collected %s from %s" v p))
      v)))

(defn all-success?
  [res]
  (every? #(= 200 %) (map #(get % "status") res)))

(defn deployer
  [req]
  (let [comps (components req)
        ports (repeatedly (count comps) chan)]
    (doseq [[[cn c] port] (partition-all 2 (interleave comps ports))]
      (log/info "Deploying component:" cn c port)
      (go
        (try
          (let [run      (deploy-comp c)
                run-uuid (url-resource run)]
            (log/info (format "Started comp %s with run %s." cn run))
            (ssr/wait-ready run-uuid)
            (if (ssr/aborted? run-uuid)
              (let [abort-msg (ssr/get-abort run-uuid)
                    body      {"status"  409
                               "message" (format "Failed to deploy component %s at %s with %s" cn run abort-msg)
                               cn        {"run-url" run}}]
                (>! port body))
              (let [hostname (ssr/get-param run-uuid "machine" nil "hostname")
                    body     {"status"  200
                              "message" "OK"
                              cn        {"hostname" hostname
                                         "port"     "5432"
                                         "run-url"  run}}]
                (log/debug "Will put result to port:" port)
                (>! port body)
                (log/debug "Successfully deployed:" body " with port" port))))
          (catch Exception e
            (>! port {"status"  409
                      "message" (.getMessage e)
                      cn        {"run-url" "No run URL."}})))))
    ports))

(defn res-comp-run-uuid
  [comp]
  (let [uuid (try
               (-> comp
                   (dissoc "status" "message")
                   vals
                   first
                   (get "run-url")
                   url-resource)
               (catch Exception e
                 (.getMessage e)))]
    (log/debug "Extracted UUID:" uuid)
    uuid))

(defn report-success
  [req res]
  (let [r (apply merge {} res)
        hookurl (hook-url req)]
    (log/info "Success. Put results." hookurl r)
    (put-json-result hookurl r)))

(defn terminate-report-failure
  [req res]
  (log/info "Failure. Terminate deployment and report.")
  (doseq [c res]
    (log/info "Terminating deployment:" c)
    (try
      (ssr/terminate (res-comp-run-uuid c))
      (catch Exception e
        (log/info (format "Failed to terminate: %s with %s." c (.getMessage e))))))
  (let [abort-msgs (for [c res :when (= 409 (get c "status"))] (get c "message"))
        msg        (format "Failed to provision. Reason(s): [%s]" (s/join "; " abort-msgs))
        hookurl    (hook-url req)]
    (log/info "Report failure:" hookurl msg)
    (put-json-failure hookurl  msg)))

(defn collector
  "req - initial request.
  ports - list of channels with future results."
  [req ports]
  (let [res (collect ports)]
    (if (all-success? res)
      (report-success req res)
      (terminate-report-failure req res))))

(defn deploy-comps
  [req]
  (->> req
       deployer
       (collector req)))

(defn orchestrate!
  [req]
  (go
    (try
      (ssauth/with-context (build-context req) (deploy-comps req))
      (catch Exception e
        (put-json-failure (hook-url req) (.getMessage e))))))

(defn deploy
  [request]
  (try
    (do (-> request
            parse-request
            orchestrate!)
        (status-202))
    (catch Exception e
      (status-404 (.getMessage e)))))

(defroutes app-routes
           (wrap-json-body
             (POST "/deploy" request (deploy request)))

           (route/not-found {:status 404 :body "Not found"}))

(defn init []
  [app-routes nil])

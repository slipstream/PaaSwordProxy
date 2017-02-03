(ns com.sixsq.slipstream.ssproxy
  "SlipStream Proxy for PaaSword project.")

(defn handler [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "SlipStream Proxy Service."})

(defn init []
  [handler nil])

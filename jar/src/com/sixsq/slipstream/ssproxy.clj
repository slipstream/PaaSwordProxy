(ns com.sixsq.slipstream.ssproxy
  "Provides a simple ring example to ensure that generic ring container works.")

(defn handler [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "SlipStream Proxy Service."})

(defn init []
  [handler nil])

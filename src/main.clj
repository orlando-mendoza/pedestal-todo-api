(ns main
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.test :as test]))

(defn response [status body & {:as headers}]
  {:status status :body body :headers headers})

(def ok (partial response 200))
(def created (partial response 201))
(def accepted (partial response 202))

;;----------------------------
;; "Database" functions

(defonce database (atom {}))

(defn find-list-by-id
  [dbval db-id]
  (get dbval db-id))

(defn find-list-item-by-ids [dbval list-id item-id]
  (get-in dbval [list-id :items item-id] nil))

(defn list-item-add
  [dbval list-id item-id new-item]
  (if (contains? dbval list-id)
    (assoc-in dbval [list-id :items item-id] new-item)
    dbval))

(defn update-item
  [dbval list-id item-id item]
  (if (contains? dbval list-id)
     (assoc-in dbval [list-id :items item-id :name] item)
    dbval))

(comment
  (update-item @database "l19320" "i19355" "Pedestal-api")
  (reset! database nil)
  ;;
  )

(def db-interceptor
  {:name :database-interceptor
   :enter
   (fn [context]
     (update context :request assoc :database @database))
   :leave
   (fn [context]
     (if-let [[op & args] (:tx-data context)]
       (do
         (apply swap! database op args)
         (assoc-in context [:request :database] @database))
       context))})

;;----------------------------
;; Domain functions

(defn make-list [nm]
  {:name nm
   :items {}})

(defn make-list-item [nm]
  {:name nm
   :done? false})

;;----------------------------
;; API Interceptors

(def echo
  {:name :echo
   :enter
   (fn [context]
     (let [request (:request context)
           response (ok context)]
       (assoc context :response response)))})

(def entity-render
  {:name :entity-render
   :leave
   (fn [context]
     (if-let [item (:result context)]
       (assoc context :response (ok item))
       context))})

(def list-query-form
  {:name :list-query-form
   :enter
   (fn [context]
     (if-let [lists (get-in context [:request :database])]
       (do
         #_ (clojure.pprint/pprint lists)
         (assoc context :result lists))
       context))})

(def list-create
  {:name :list-create
   :enter
   (fn [context]
     (let [nm (get-in context [:request :query-params :name] "Unnamed List")
           new-list (make-list nm)
           db-id (str (gensym "l"))
           url (route/url-for :list-view :params {:list-id db-id})]
       (assoc context
              :response (created new-list "Location" url)
              :tx-data [assoc db-id new-list])))})

(def list-view
  {:name :list-view
   :enter
   (fn [context]
     (if-let [db-id (get-in context [:request :path-params :list-id])]
       (if-let [the-list (find-list-by-id (get-in context [:request :database]) db-id)]
         (assoc context :result the-list)
         context)
       context))})

(def list-item-view
  {:name :list-item-view
   :leave
   (fn [context]
     (if-let [list-id (get-in context [:request :path-params :list-id])]
       (if-let [item-id (get-in context [:request :path-params :item-id])]
         (if-let [item (find-list-item-by-ids (get-in context [:request :database]) list-id item-id)]
           (assoc context :result item)
           context)
         context)
       context))})

(def list-item-update
  {:name :list-item-update
   :leave
   (fn [context]
     (if-let [list-id (get-in context [:request :path-params :list-id])]
       (if-let [item-id (get-in context [:request :path-params :item-id])]
         (if-let [name (get-in context [:request :query-params :name])]
           (-> context
               (assoc :tx-data [update-item list-id item-id name]))
           context)
         context)
       context))})

(def list-item-create
  {:name :list-item-create
   :enter
   (fn [context]
     (if-let [list-id (get-in context [:request :path-params :list-id])]
       (let [nm (get-in context [:request :query-params :name] "Unnamed Item")
             new-item (make-list-item nm)
             item-id (str (gensym "i"))]
         (-> context
             (assoc :tx-data [list-item-add list-id item-id new-item])
             (assoc-in [:request :path-params :item-id] item-id)))
       context))})

(def routes
  (route/expand-routes
   #{["/todo" :post [db-interceptor list-create]]
     ["/todo" :get  [entity-render db-interceptor list-query-form]]
     ["/todo/:list-id" :get [entity-render db-interceptor list-view]]
     ["/todo/:list-id" :post [entity-render list-item-view db-interceptor list-item-create]]
     ["/todo/:list-id/:item-id" :get [entity-render list-item-view db-interceptor]]
     ["/todo/:list-id/:item-id" :put [entity-render db-interceptor list-item-update]]
     ["/todo/:list-id/:item-id" :delete echo :route-name :list-item-delete]}))

(def service-map
  {::http/routes routes
   ::http/type   :jetty
   ::http/port   8894})

(defn start []
  (http/start (http/create-server service-map)))

;; For interactive development
(defonce server (atom nil))

(defn start-dev []
  (reset! server
          (http/start (http/create-server
                       (assoc service-map
                              ::http/join? false)))))

(defn stop-dev []
  (http/stop @server))

(defn restart []
  (stop-dev)
  (start-dev)
  ;;
  )

(defn test-request [verb url]
  (io.pedestal.test/response-for (::http/service-fn @server) verb url))

(comment
  (require '[io.pedestal.test :as test])
  (user/portal)
  (start-dev)
  (restart)

  (main/test-request :get "/todo")

  (tap> (main/test-request :post "/todo?name=A-list"))
  (tap> (main/test-request :post "/todo/l19320?name=Pedetal"))
  (tap> (main/test-request :get "/todo/l25069/i25090"))
  (tap> (main/test-request :put "/todo/l19320/i19336?name=Lacinia"))
  (main/test-request :put "/todo/l19320/i19355?name=Pedestal")

  (tap> :hello)

  (tap> @database)
  ;; test the echo route
  (test/response-for (:io.pedestal.http/service-fn @main/server) :get "/todo")

  ;; Testing methods and routes that doesn't exists
  (tap> (dissoc (test/response-for (:io.pedestal.http/service-fn @main/server) :get "/no-such-route") :body))
  ;; => {:status 404, :headers {"Content-Type" "text/plain"}}
  (dissoc (test/response-for (:io.pedestal.http/service-fn @main/server) :delete "/todo") :body)
  ;; => {:status 404, :headers {"Content-Type" "text/plain"}}

  (tap> (test/response-for (:io.pedestal.http/service-fn @main/server) :post "/todo/l21545?name=Pedetal"))
  (test/response-for (:io.pedestal.http/service-fn @main/server) :put "/todo/l25394/i27296?name=FireGuns")


  (= [1] '(1))
  (reset! database nil)
  ;;
  )





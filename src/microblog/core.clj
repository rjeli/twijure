(ns microblog.core
  (:require [compojure.handler :as handler]
            [taoensso.carmine :as car :refer (wcar)]
            [clojure.string :as str]
            [compojure.route :as route]
            [clojure.data.json :as json]
            [ring.mock.request :as mock])
  (:use [ring.adapter.jetty]
        [ring.util.response]
        [ring.middleware.session]
        [ring.middleware.content-type]
        [ring.middleware.reload]
        [ring.middleware.session.memory]
        [ring.middleware.params]
        [ring.middleware.keyword-params]
        [compojure.core]
        [clojure.test]
        [selmer.parser]))

(def redis-connection {:pool {} :spec {}})
(defmacro wcar* [& body] `(car/wcar redis-connection ~@body))

(if (nil? (wcar* (car/get "next_user_id")))
  (wcar* (car/set "next_user_id" 1000)))

(if (nil? (wcar* (car/get "next_post_id")))
  (wcar* (car/set "next_post_id" 1000)))

(defn select-values [map ks] (reduce #(conj %1 (map %2)) [] ks))

(defn p-tagify
  [string]
  (str "<p>" string "</p>"))

(defn auth-wrapper
  [handler]
  (fn
    [request]
    (if (-> request :session :uid)
      (handler request)
      (file-response "resources/login.html"))))

(defn root-handler
  [request]
  (let [uid (-> request :session :uid)
        [posts username] (wcar* (car/lrange (str "feed:" uid) 0 10)
                                (car/hget (str "user:" uid) "username"))]
    (render-file "feed.html" {:username username
                              :posts (for [pid posts]
                                       (let [[author-id body] (wcar*
                                                                (car/hget (str "post:" pid) "author")
                                                                (car/hget (str "post:" pid) "body"))
                                             author-name (wcar* (car/hget (str "user:" author-id) "username"))]
                                         {:author author-name
                                          :body body
                                          :id pid}))})))

(defn user-handler
  [request]
  (let [name (-> request :params :name)
        id (wcar* (car/hget "users" name))
        posts (wcar* (car/lrange (str "posts:" id) 0 10))
        uid (-> request :session :uid) ]
    (render-file "user.html" {:username name
                              :id id
                              :following (= 1 (wcar* (car/sismember (str "following:" uid) id)))
                              :posts (for [pid posts] 
                                       {:id pid
                                        :body (wcar* (car/hget (str "post:" pid) "body"))})})))

(defn post-handler
  [request]
  (let [id (-> request :params :id)
        uid (-> request :session :uid)
        [username author-id body] (wcar* 
                                    (car/hget (str "user:" uid) "username")
                                    (car/hget (str "post:" id) "author")
                                    (car/hget (str "post:" id) "body"))
        author-name (wcar* (car/hget (str "user:" author-id) "username"))]
    (render-file "post.html" {:username username
                              :author author-name
                              :post body})))

(defn newpost-handler
  [request]
  (let [uid (-> request :session :uid)
        username (wcar* (car/hget (str "user:" uid) "username"))]
    (render-file "newpost.html" {:username username})))

(defn login-handler
  [request]
  (let [username (-> request :params :username)
        password (-> request :params :password)
        session (:session request)
        uid (wcar* (car/hget "users" username))
        account-password (wcar* (car/hget (str "user:" uid) "password"))]
    (if (= password account-password)
      (->
        (redirect "/")
        (assoc :session (assoc session :uid uid)))
      (response (str "failure, provided pass was " password " and correct pass was " account-password)))))

(defn session-handler
  [request]
  (println "received session req")
  (response (str (:session request))))

(defn new-user
  [request]
  (response "new user action!!"))

(defn new-post
  [request]
  (let [author-id (-> request :session :uid)
        body (-> request :params :post-body)
        post-id (wcar* (car/incr "next_post_id"))]
    (wcar*
      (car/hmset (str "post:" post-id) "author" author-id "body" body)
      (doseq [f (wcar* (car/smembers (str "followers:" author-id)))]
        (car/lpush (str "feed:" f) post-id))
      (car/lpush (str "posts:" author-id) post-id))
    (redirect "/")))

(defn follow-user
  [request]
  (let [user-id (-> request :params :id)
        follower-id (-> request :session :uid)]
    (println follower-id " is following " user-id)
    (wcar*
      (car/sadd (str "followers:" user-id) follower-id)
      (car/sadd (str "following:" follower-id) user-id))
    (redirect (str "/user/" (wcar* (car/hget (str "user:" user-id) "username"))))))

(defn unfollow-user
  [request]
  (let [user-id (-> request :params :id)
        unfollower-id (-> request :session :uid)]
    (wcar*
      (car/srem (str "followers:" user-id) unfollower-id)
      (car/srem (str "following:" unfollower-id) user-id))
    (redirect (str "/user/" (wcar* (car/hget (str "user:" user-id) "username"))))))

(defn logout-handler
  [request]
  (let [session (:session request)]
    (->
      (redirect "/")
      (assoc :session (dissoc session :uid)))))


(defn action-handler
  [request]
  (let [action (-> request :params :action)]
    ((case action
       "login" login-handler
       "newuser" new-user
       "newpost" new-post
       "follow" follow-user
       "unfollow" unfollow-user
       "logout" logout-handler) request)))


(defroutes router

  (GET "/" [] (auth-wrapper root-handler))
  (GET "/user/:name" [] user-handler)
  (GET "/post/:id" [] post-handler)
  (GET "/post" [] newpost-handler)
  (GET "/register" [] (file-response "resources/register.html"))
  (GET "/session" [] session-handler)

  (POST "/action" [] action-handler)

  (route/resources "/")
  (route/not-found "Page not found"))

(def app
  (-> router
      (wrap-reload)
      (wrap-session)
      (wrap-keyword-params)
      (wrap-params)))

(deftest test-redis
  (testing "ping pong"
    (is (= (wcar* (car/ping)) "PONG"))
    (is (= (wcar* (car/set "chong" "bar") "OK")))
    (is (= (wcar* (car/get "chong")) "bar"))))

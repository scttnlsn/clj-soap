(ns clj-soap.core
  (:require [clojure.core.incubator :refer [-?>]]))

;;; Defining SOAP Server

(defn flatten1 [coll] (mapcat identity coll))

(defn gen-class-method-decls [method-defs]
  (flatten1
    (letfn [(gen-decl [method-name arglist body]
              [method-name
               (vec (for [arg arglist] (or (:tag (meta arg)) String)))
               (or (:tag (meta arglist)) 'void)])]
      (for [method-def method-defs]
        (cond
          (vector? (second method-def))
          (list (let [[method-name arglist & body] method-def]
                  (gen-decl method-name arglist body)))
          (seq? (second method-def))
          (let [[method-name & deflist] method-def]
            (for [[arglist & body] deflist]
              (gen-decl method-name arglist body))))))))

(defn gen-method-defs [prefix method-defs]
  (flatten1
    (for [method-def method-defs]
      (cond
        (vector? (second method-def))
        (list (let [[method-name arglist & body] method-def]
                `(defn ~(symbol (str prefix method-name))
                   ~(vec (cons 'this arglist)) ~@body)))
        (seq? (second method-def))
        (let [[method-name & deflist] method-def]
          (cons
            `(defmulti ~(symbol (str prefix method-name))
               (fn [~'this & args#] (vec (map class args#))))
            (for [[arglist & body] deflist]
              `(defmethod ~(symbol (str prefix method-name))
                 ~(vec (map #(:tag (meta %)) arglist))
                 ~(vec (cons 'this arglist)) ~@body))))))))


(defmacro defservice
  "Define SOAP class.
  i.e. (defsoap some.package.KlassName (myfunc [String a int b] String (str a (* b b))))"
  [class-name & method-defs]
  (let [prefix (str (gensym "prefix"))]
    `(do
       (gen-class
         :name ~class-name
         :prefix ~prefix
         :methods ~(vec (gen-class-method-decls method-defs)))
       ~@(gen-method-defs prefix method-defs))))

(defn serve
  "Start SOAP server.
  argument classes is list of strings of classnames."
  [& classes]
  (let [server (org.apache.axis2.engine.AxisServer.)]
    (doseq [c classes]
      (.deployService server (str c)))))

;; Client call

(defn axis-service-namespace [axis-service]
  (.get (.getNamespaceMap axis-service) "ns"))

(defn axis-service-operations [axis-service]
  (iterator-seq (.getOperations axis-service)))

(defn axis-op-name [axis-op]
  (.getLocalPart (.getName axis-op)))

(defn axis-op-namespace [axis-op]
  (.getNamespaceURI (.getName axis-op)))

(defn axis-op-args [axis-op]
  (for [elem (-?> (first (filter #(= "out" (.getDirection %))
                                 (iterator-seq (.getMessages axis-op))))
                  .getSchemaElement .getSchemaType
                  .getParticle .getItems .getIterator iterator-seq)]
    {:name (.getName elem) :type (-?> elem .getSchemaType .getName keyword)}))

(defn axis-op-rettype [axis-op]
  (-?> (first (filter #(= "in" (.getDirection %))
                      (iterator-seq (.getMessages axis-op))))
       .getSchemaElement .getSchemaType .getParticle .getItems .getIterator
       iterator-seq first
       .getSchemaType .getName
       keyword))

(defmulti obj->soap-str (fn [obj argtype] argtype))

(defmethod obj->soap-str :integer [obj argtype] (str obj))
(defmethod obj->soap-str :double [obj argtype] (str obj))
(defmethod obj->soap-str :string [obj argtype] (str obj))
(defmethod obj->soap-str :boolean [obj argtype] (str obj))
(defmethod obj->soap-str :default [obj argtype] (str obj))

(defmulti soap-elem->obj (fn [elem argtype] argtype))

(defmethod soap-elem->obj :integer
  [elem argtype]
  (Integer/parseInt (.getText elem)))

(defmethod soap-elem->obj :double
  [elem argtype]
  (Double/parseDouble (.getText elem)))

(defmethod soap-elem->obj :string
  [elem argtype]
  (.getText elem))

(defmethod soap-elem->obj :boolean
  [elem argtype]
  (Boolean/parseBoolean (.getText elem)))

(defmethod soap-elem->obj :default
  [elem argtype]
  (.getText elem))

(defn make-client [url-or-client]
  (if (string? url-or-client)
    (doto (org.apache.axis2.client.ServiceClient. nil (java.net.URL. url-or-client) nil nil)
      (.setOptions
       (doto (org.apache.axis2.client.Options.)
         (.setTo (org.apache.axis2.addressing.EndpointReference. url-or-client)))))
    url-or-client))

(defn make-request [op & args]
  (let [factory (org.apache.axiom.om.OMAbstractFactory/getOMFactory)
        request (.createOMElement
                 factory (javax.xml.namespace.QName.
                          (axis-op-namespace op) (axis-op-name op)))
        op-args (axis-op-args op)]
    (doseq [[argval argtype] (map list args op-args)]
      (.addChild request
                 (doto (.createOMElement
                        factory (javax.xml.namespace.QName. (:name argtype)))
                   (.setText (obj->soap-str argval (:type argtype))))))
    request))

(defn get-result [op retelem]
  (let [elem (first (iterator-seq (.getChildElements retelem)))]
    (soap-elem->obj elem (axis-op-rettype op))))

(defn client-call [client op & args]
  (if (isa? (class op) org.apache.axis2.description.OutOnlyAxisOperation)
    (.sendRobust client (.getName op) (apply make-request op args))
    (get-result
      op (.sendReceive client (.getName op) (apply make-request op args)))))

(defn client-proxy [url-or-client]
  (let [client (make-client url-or-client)]
    (->> (for [op (axis-service-operations (.getAxisService client))]
           [(keyword (axis-op-name op))
            (fn soap-call [& args] (apply client-call client op args))])
         (into {}))))

(defn client-fn
  "Make SOAP client function, which is called as: (x :someMethod arg1 arg2 ...)"
  [url-or-client]
  (let [px (client-proxy url-or-client)]
    (fn [opname & args]
      (apply (px opname) args))))

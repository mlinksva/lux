(ns lux.host
  (:require (clojure [string :as string]
                     [template :refer [do-template]])
            [clojure.core.match :refer [match]]
            (lux [util :as &util :refer [exec return* return fail fail*
                                         repeat-m try-all-m map-m mapcat-m reduce-m
                                         within
                                         normalize-ident]]
                 [parser :as &parser]
                 [type :as &type])))

;; [Utils]
(defn ^:private class->type [class]
  (if-let [[_ base arr-level] (re-find #"^([^\[]+)(\[\])*$"
                                       (str (if-let [pkg (.getPackage class)]
                                              (str (.getName pkg) ".")
                                              "")
                                            (.getSimpleName class)))]
    (if (= "void" base)
      (return [::&type/Nothing])
      (let [base* [::&type/Data base]]
        (if arr-level
          (return (reduce (fn [inner _]
                            [::&type/Array inner])
                          base*
                          (range (/ (count arr-level) 2.0))))
          (return base*)))
      )))

(defn ^:private method->type [method]
  (exec [=args (map-m class->type (seq (.getParameterTypes method)))
         =return (class->type (.getReturnType method))]
    (return [=args =return])))

;; [Resources]
(defn full-class [class-name]
  (case class
    "boolean" (return Boolean/TYPE)
    "byte"    (return Byte/TYPE)
    "short"   (return Short/TYPE)
    "int"     (return Integer/TYPE)
    "long"    (return Long/TYPE)
    "float"   (return Float/TYPE)
    "double"  (return Double/TYPE)
    "char"    (return Character/TYPE)
    ;; else
    (try (return (Class/forName class-name))
      (catch Exception e
        (fail "[Analyser Error] Unknown class.")))))

(defn full-class-name [class-name]
  (exec [=class (full-class class-name)]
    (.getName class-name)))

(defn ->class [class]
  (string/replace class #"\." "/"))

(defn extract-jvm-param [token]
  (match token
    [::&parser/ident ?ident]
    (full-class-name ?ident)

    [::&parser/form ([[::&parser/ident "Array"] [::&parser/ident ?inner]] :seq)]
    (exec [=inner (full-class-name ?inner)]
      (return (str "[L" (->class =inner) ";")))

    _
    (fail "")))

(do-template [<name> <static?>]
  (defn <name> [target field]
    (if-let [type* (first (for [=field (.getFields target)
                                :when (and (= target (.getDeclaringClass =field))
                                           (= field (.getName =field))
                                           (= <static?> (java.lang.reflect.Modifier/isStatic (.getModifiers =field))))]
                            (.getType =field)))]
      (exec [=type (&type/class->type type*)]
        (return =type))
      (fail (str "[Analyser Error] Field does not exist: " target field))))

  lookup-static-field true
  lookup-field        false
  )

(do-template [<name> <static?>]
  (defn <name> [target method-name args]
    (if-let [method (first (for [=method (.getMethods target)
                                 :when (and (= target (.getDeclaringClass =method))
                                            (= method-name (.getName =method))
                                            (= <static?> (java.lang.reflect.Modifier/isStatic (.getModifiers =method))))]
                             =method))]
      (exec [=method (&type/method->type method)]
        (return =method))
      (fail (str "[Analyser Error] Method does not exist: " target method-name))))

  lookup-static-method  true
  lookup-virtual-method false
  )

(defn location [scope]
  (->> scope reverse (map normalize-ident) (interpose "$") (reduce str "")))

;;  Copyright (c) Eduardo Julian. All rights reserved.
;;  This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
;;  If a copy of the MPL was not distributed with this file,
;;  You can obtain one at http://mozilla.org/MPL/2.0/.

(ns lux.analyser.host
  (:require (clojure [template :refer [do-template]]
                     [string :as string])
            clojure.core.match
            clojure.core.match.array
            (lux [base :as & :refer [|let |do return* return fail |case assert!]]
                 [type :as &type]
                 [host :as &host])
            [lux.type.host :as &host-type]
            (lux.analyser [base :as &&]
                          [lambda :as &&lambda]
                          [env :as &&env]
                          [parser :as &&a-parser])
            [lux.compiler.base :as &c!base])
  (:import (java.lang.reflect Type TypeVariable)))

;; [Utils]
(defn ^:private ensure-catching [exceptions]
  "(-> (List Text) (Lux Null))"
  (|do [class-loader &/loader]
    (fn [state]
      (let [exceptions (&/|map #(Class/forName % true class-loader) exceptions)
            catching (->> state (&/get$ &/$host) (&/get$ &/$catching)
                          (&/|map #(Class/forName % true class-loader)))]
        (if-let [missing-ex (&/fold (fn [prev ^Class now]
                                      (or prev
                                          (if (&/fold (fn [found? ^Class ex-catch]
                                                        (or found?
                                                            (.isAssignableFrom ex-catch now)))
                                                      false
                                                      catching)
                                            nil
                                            now)))
                                    nil
                                    exceptions)]
          (&/fail* (str "[Analyser Error] Unhandled exception: " missing-ex))
          (&/return* state nil)))
      )))

(defn ^:private with-catches [catches body]
  "(All [a] (-> (List Text) (Lux a) (Lux a)))"
  (fn [state]
    (let [old-catches (->> state (&/get$ &/$host) (&/get$ &/$catching))
          state* (->> state (&/update$ &/$host #(&/update$ &/$catching (partial &/|++ catches) %)))]
      (|case (&/run-state body state*)
        (&/$Left msg)
        (&/$Left msg)

        (&/$Right state** output)
        (&/$Right (&/T [(->> state** (&/update$ &/$host #(&/set$ &/$catching old-catches %)))
                        output]))))
    ))

(defn ^:private ensure-object [type]
  "(-> Type (Lux (, Text (List Type))))"
  (|case type
    (&/$DataT payload)
    (return payload)

    (&/$VarT id)
    (return (&/T ["java.lang.Object" (&/|list)]))

    (&/$ExT id)
    (return (&/T ["java.lang.Object" (&/|list)]))

    (&/$NamedT _ type*)
    (ensure-object type*)

    (&/$UnivQ _ type*)
    (ensure-object type*)

    (&/$ExQ _ type*)
    (ensure-object type*)

    (&/$AppT F A)
    (|do [type* (&type/apply-type F A)]
      (ensure-object type*))

    _
    (fail (str "[Analyser Error] Expecting object: " (&type/show-type type)))))

(defn ^:private as-object [type]
  "(-> Type Type)"
  (|case type
    (&/$DataT class params)
    (&/$DataT (&host-type/as-obj class) params)

    _
    type))

(defn ^:private as-otype [tname]
  (case tname
    "boolean" "java.lang.Boolean"
    "byte"    "java.lang.Byte"
    "short"   "java.lang.Short"
    "int"     "java.lang.Integer"
    "long"    "java.lang.Long"
    "float"   "java.lang.Float"
    "double"  "java.lang.Double"
    "char"    "java.lang.Character"
    ;; else
    tname
    ))

(defn ^:private as-otype+ [type]
  "(-> Type Type)"
  (|case type
    (&/$DataT name params)
    (&/$DataT (as-otype name) params)

    _
    type))

(defn ^:private clean-gtype-var [idx gtype-var]
  (|let [(&/$VarT id) gtype-var]
    (|do [? (&type/bound? id)]
      (if ?
        (|do [real-type (&type/deref id)]
          (return (&/T [idx real-type])))
        (return (&/T [(+ 2 idx) (&/$BoundT idx)]))))))

(defn ^:private clean-gtype-vars [gtype-vars]
  (|do [[_ clean-types] (&/fold% (fn [idx+types gtype-var]
                                   (|do [:let [[idx types] idx+types]
                                         [idx* real-type] (clean-gtype-var idx gtype-var)]
                                     (return (&/T [idx* (&/$Cons real-type types)]))))
                                 (&/T [1 &/$Nil])
                                 gtype-vars)]
    (return clean-types)))

(defn ^:private make-gtype [class-name type-args]
  "(-> Text (List Type) Type)"
  (&/fold (fn [base-type type-arg]
            (|case type-arg
              (&/$BoundT _)
              (&/$UnivQ &type/empty-env base-type)
              
              _
              base-type))
          (&/$DataT class-name type-args)
          type-args))

;; [Resources]
(do-template [<name> <output-tag> <input-class> <output-class>]
  (let [input-type (&/$DataT <input-class> &/$Nil)
        output-type (&/$DataT <output-class> &/$Nil)]
    (defn <name> [analyse exo-type x y]
      (|do [=x (&&/analyse-1 analyse input-type x)
            =y (&&/analyse-1 analyse input-type y)
            _ (&type/check exo-type output-type)
            _cursor &/cursor]
        (return (&/|list (&&/|meta output-type _cursor
                                   (<output-tag> (&/T [=x =y]))))))))

  analyse-jvm-iadd &&/$jvm-iadd "java.lang.Integer" "java.lang.Integer"
  analyse-jvm-isub &&/$jvm-isub "java.lang.Integer" "java.lang.Integer"
  analyse-jvm-imul &&/$jvm-imul "java.lang.Integer" "java.lang.Integer"
  analyse-jvm-idiv &&/$jvm-idiv "java.lang.Integer" "java.lang.Integer"
  analyse-jvm-irem &&/$jvm-irem "java.lang.Integer" "java.lang.Integer"
  analyse-jvm-ieq  &&/$jvm-ieq  "java.lang.Integer" "java.lang.Boolean"
  analyse-jvm-ilt  &&/$jvm-ilt  "java.lang.Integer" "java.lang.Boolean"
  analyse-jvm-igt  &&/$jvm-igt  "java.lang.Integer" "java.lang.Boolean"

  analyse-jvm-ceq  &&/$jvm-ceq  "java.lang.Character" "java.lang.Boolean"
  analyse-jvm-clt  &&/$jvm-clt  "java.lang.Character" "java.lang.Boolean"
  analyse-jvm-cgt  &&/$jvm-cgt  "java.lang.Character" "java.lang.Boolean"

  analyse-jvm-ladd &&/$jvm-ladd "java.lang.Long"    "java.lang.Long"
  analyse-jvm-lsub &&/$jvm-lsub "java.lang.Long"    "java.lang.Long"
  analyse-jvm-lmul &&/$jvm-lmul "java.lang.Long"    "java.lang.Long"
  analyse-jvm-ldiv &&/$jvm-ldiv "java.lang.Long"    "java.lang.Long"
  analyse-jvm-lrem &&/$jvm-lrem "java.lang.Long"    "java.lang.Long"
  analyse-jvm-leq  &&/$jvm-leq  "java.lang.Long"    "java.lang.Boolean"
  analyse-jvm-llt  &&/$jvm-llt  "java.lang.Long"    "java.lang.Boolean"
  analyse-jvm-lgt  &&/$jvm-lgt  "java.lang.Long"    "java.lang.Boolean"

  analyse-jvm-fadd &&/$jvm-fadd "java.lang.Float"   "java.lang.Float"
  analyse-jvm-fsub &&/$jvm-fsub "java.lang.Float"   "java.lang.Float"
  analyse-jvm-fmul &&/$jvm-fmul "java.lang.Float"   "java.lang.Float"
  analyse-jvm-fdiv &&/$jvm-fdiv "java.lang.Float"   "java.lang.Float"
  analyse-jvm-frem &&/$jvm-frem "java.lang.Float"   "java.lang.Float"
  analyse-jvm-feq  &&/$jvm-feq  "java.lang.Float"   "java.lang.Boolean"
  analyse-jvm-flt  &&/$jvm-flt  "java.lang.Float"   "java.lang.Boolean"
  analyse-jvm-fgt  &&/$jvm-fgt  "java.lang.Float"   "java.lang.Boolean"

  analyse-jvm-dadd &&/$jvm-dadd "java.lang.Double"  "java.lang.Double"
  analyse-jvm-dsub &&/$jvm-dsub "java.lang.Double"  "java.lang.Double"
  analyse-jvm-dmul &&/$jvm-dmul "java.lang.Double"  "java.lang.Double"
  analyse-jvm-ddiv &&/$jvm-ddiv "java.lang.Double"  "java.lang.Double"
  analyse-jvm-drem &&/$jvm-drem "java.lang.Double"  "java.lang.Double"
  analyse-jvm-deq  &&/$jvm-deq  "java.lang.Double"  "java.lang.Boolean"
  analyse-jvm-dlt  &&/$jvm-dlt  "java.lang.Double"  "java.lang.Boolean"
  analyse-jvm-dgt  &&/$jvm-dgt  "java.lang.Double"  "java.lang.Boolean"
  )

(defn ^:private analyse-field-access-helper [obj-type gvars gtype]
  "(-> Type (List (^ java.lang.reflect.Type)) (^ java.lang.reflect.Type) (Lux Type))"
  (|case obj-type
    (&/$DataT class targs)
    (if (= (&/|length targs) (&/|length gvars))
      (|let [gtype-env (&/fold2 (fn [m ^TypeVariable g t] (&/$Cons (&/T [(.getName g) t]) m))
                                (&/|table)
                                gvars
                                targs)]
        (&host-type/instance-param &type/existential gtype-env gtype))
      (fail (str "[Type Error] Mismatched number of type-parameters: " (&/|length gvars) " - " (&type/show-type obj-type))))

    _
    (fail (str "[Type Error] Type is not an object type: " (&type/show-type obj-type)))))

(defn analyse-jvm-getstatic [analyse exo-type class field]
  (|do [class-loader &/loader
        [gvars gtype] (&host/lookup-static-field class-loader class field)
        =type (&host-type/instance-param &type/existential &/$Nil gtype)
        :let [output-type =type]
        _ (&type/check exo-type output-type)
        _cursor &/cursor]
    (return (&/|list (&&/|meta exo-type _cursor
                               (&&/$jvm-getstatic (&/T [class field output-type])))))))

(defn analyse-jvm-getfield [analyse exo-type class field object]
  (|do [class-loader &/loader
        =object (&&/analyse-1+ analyse object)
        _ (ensure-object (&&/expr-type* =object))
        [gvars gtype] (&host/lookup-field class-loader class field)
        =type (analyse-field-access-helper (&&/expr-type* =object) gvars gtype)
        :let [output-type =type]
        _ (&type/check exo-type output-type)
        _cursor &/cursor]
    (return (&/|list (&&/|meta exo-type _cursor
                               (&&/$jvm-getfield (&/T [class field =object output-type])))))))

(defn analyse-jvm-putstatic [analyse exo-type class field value]
  (|do [class-loader &/loader
        [gvars gtype] (&host/lookup-static-field class-loader class field)
        :let [gclass (&host-type/gtype->gclass gtype)]
        =type (&host-type/instance-param &type/existential &/$Nil gtype)
        =value (&&/analyse-1 analyse =type value)
        :let [output-type &/$UnitT]
        _ (&type/check exo-type output-type)
        _cursor &/cursor]
    (return (&/|list (&&/|meta exo-type _cursor
                               (&&/$jvm-putstatic (&/T [class field =value gclass])))))))

(defn analyse-jvm-putfield [analyse exo-type class field value object]
  (|do [class-loader &/loader
        =object (&&/analyse-1+ analyse object)
        :let [obj-type (&&/expr-type* =object)]
        _ (ensure-object obj-type)
        [gvars gtype] (&host/lookup-field class-loader class field)
        :let [gclass (&host-type/gtype->gclass gtype)]
        =type (analyse-field-access-helper obj-type gvars gtype)
        =value (&&/analyse-1 analyse =type value)
        :let [output-type &/$UnitT]
        _ (&type/check exo-type output-type)
        _cursor &/cursor]
    (return (&/|list (&&/|meta exo-type _cursor
                               (&&/$jvm-putfield (&/T [class field =value gclass =object =type])))))))

(defn analyse-jvm-instanceof [analyse exo-type class object]
  (|do [=object (&&/analyse-1+ analyse object)
        _ (ensure-object (&&/expr-type* =object))
        :let [output-type &type/Bool]
        _ (&type/check exo-type output-type)
        _cursor &/cursor]
    (return (&/|list (&&/|meta output-type _cursor
                               (&&/$jvm-instanceof (&/T [class =object])))))))

(defn ^:private analyse-method-call-helper [analyse gret gtype-env gtype-vars gtype-args args]
  (|case gtype-vars
    (&/$Nil)
    (|do [arg-types (&/map% (partial &host-type/instance-param &type/existential gtype-env) gtype-args)
          =arg-types (&/map% &type/show-type+ arg-types)
          =args (&/map2% (partial &&/analyse-1 analyse) arg-types args)
          =gret (&host-type/instance-param &type/existential gtype-env gret)]
      (return (&/T [=gret =args])))
    
    (&/$Cons ^TypeVariable gtv gtype-vars*)
    (&type/with-var
      (fn [$var]
        (|do [:let [gtype-env* (&/$Cons (&/T [(.getName gtv) $var]) gtype-env)]
              [=gret =args] (analyse-method-call-helper analyse gret gtype-env* gtype-vars* gtype-args args)
              ==gret (&type/clean $var =gret)
              ==args (&/map% (partial &&/clean-analysis $var) =args)]
          (return (&/T [==gret ==args])))))
    ))

(let [dummy-type-param (&/$DataT "java.lang.Object" &/$Nil)]
  (do-template [<name> <tag> <only-interface?>]
    (defn <name> [analyse exo-type class method classes object args]
      (|do [class-loader &/loader
            _ (try (assert! (let [=class (Class/forName class true class-loader)]
                              (= <only-interface?> (.isInterface =class)))
                            (if <only-interface?>
                              (str "[Analyser Error] Can only invoke method \"" method "\"" " on interface.")
                              (str "[Analyser Error] Can only invoke method \"" method "\"" " on class.")))
                (catch Exception e
                  (fail (str "[Analyser Error] Unknown class: " class))))
            [gret exceptions parent-gvars gvars gargs] (if (= "<init>" method)
                                                         (return (&/T [Void/TYPE &/$Nil &/$Nil &/$Nil &/$Nil]))
                                                         (&host/lookup-virtual-method class-loader class method classes))
            _ (ensure-catching exceptions)
            =object (&&/analyse-1+ analyse object)
            [sub-class sub-params] (ensure-object (&&/expr-type* =object))
            (&/$DataT super-class* super-params*) (&host-type/->super-type &type/existential class-loader class sub-class sub-params)
            :let [gtype-env (&/fold2 (fn [m ^TypeVariable g t] (&/$Cons (&/T [(.getName g) t]) m))
                                     (&/|table)
                                     parent-gvars
                                     super-params*)]
            [output-type =args] (analyse-method-call-helper analyse gret gtype-env gvars gargs args)
            _ (&type/check exo-type (as-otype+ output-type))
            _cursor &/cursor]
        (return (&/|list (&&/|meta exo-type _cursor
                                   (<tag> (&/T [class method classes =object =args output-type])))))))

    analyse-jvm-invokevirtual   &&/$jvm-invokevirtual   false
    analyse-jvm-invokespecial   &&/$jvm-invokespecial   false
    analyse-jvm-invokeinterface &&/$jvm-invokeinterface true
    ))

(defn analyse-jvm-invokestatic [analyse exo-type class method classes args]
  (|do [class-loader &/loader
        [gret exceptions parent-gvars gvars gargs] (&host/lookup-static-method class-loader class method classes)
        _ (ensure-catching exceptions)
        :let [gtype-env (&/|table)]
        [output-type =args] (analyse-method-call-helper analyse gret gtype-env gvars gargs args)
        _ (&type/check exo-type (as-otype+ output-type))
        _cursor &/cursor]
    (return (&/|list (&&/|meta exo-type _cursor
                               (&&/$jvm-invokestatic (&/T [class method classes =args output-type])))))))

(defn analyse-jvm-null? [analyse exo-type object]
  (|do [=object (&&/analyse-1+ analyse object)
        _ (ensure-object (&&/expr-type* =object))
        :let [output-type &type/Bool]
        _ (&type/check exo-type output-type)
        _cursor &/cursor]
    (return (&/|list (&&/|meta output-type _cursor
                               (&&/$jvm-null? =object))))))

(defn analyse-jvm-null [analyse exo-type]
  (|do [:let [output-type (&/$DataT &host-type/null-data-tag &/$Nil)]
        _ (&type/check exo-type output-type)
        _cursor &/cursor]
    (return (&/|list (&&/|meta output-type _cursor
                               &&/$jvm-null)))))

(defn ^:private analyse-jvm-new-helper [analyse gtype gtype-env gtype-vars gtype-args args]
  (|case gtype-vars
    (&/$Nil)
    (|do [arg-types (&/map% (partial &host-type/instance-param &type/existential gtype-env) gtype-args)
          =args (&/map2% (partial &&/analyse-1 analyse) arg-types args)
          gtype-vars* (->> gtype-env (&/|map &/|second) (clean-gtype-vars))]
      (return (&/T [(make-gtype gtype gtype-vars*)
                    =args])))
    
    (&/$Cons ^TypeVariable gtv gtype-vars*)
    (&type/with-var
      (fn [$var]
        (|do [:let [gtype-env* (&/$Cons (&/T [(.getName gtv) $var]) gtype-env)]
              [=gret =args] (analyse-jvm-new-helper analyse gtype gtype-env* gtype-vars* gtype-args args)
              ==gret (&type/clean $var =gret)
              ==args (&/map% (partial &&/clean-analysis $var) =args)]
          (return (&/T [==gret ==args])))))
    ))

(defn analyse-jvm-new [analyse exo-type class classes args]
  (|do [class-loader &/loader
        [exceptions gvars gargs] (&host/lookup-constructor class-loader class classes)
        _ (ensure-catching exceptions)
        [output-type =args] (analyse-jvm-new-helper analyse class (&/|table) gvars gargs args)
        _ (&type/check exo-type output-type)
        _cursor &/cursor]
    (return (&/|list (&&/|meta exo-type _cursor
                               (&&/$jvm-new (&/T [class classes =args])))))))

(let [length-type &type/Int
      idx-type &type/Int]
  (do-template [<elem-class> <array-class> <new-name> <new-tag> <load-name> <load-tag> <store-name> <store-tag>]
    (let [elem-type (&/$DataT <elem-class> &/$Nil)
          array-type (&/$DataT <array-class> &/$Nil)]
      (defn <new-name> [analyse exo-type length]
        (|do [=length (&&/analyse-1 analyse length-type length)
              _ (&type/check exo-type array-type)
              _cursor &/cursor]
          (return (&/|list (&&/|meta exo-type _cursor
                                     (<new-tag> =length))))))

      (defn <load-name> [analyse exo-type array idx]
        (|do [=array (&&/analyse-1 analyse array-type array)
              =idx (&&/analyse-1 analyse idx-type idx)
              _ (&type/check exo-type elem-type)
              _cursor &/cursor]
          (return (&/|list (&&/|meta exo-type _cursor
                                     (<load-tag> (&/T [=array =idx])))))))

      (defn <store-name> [analyse exo-type array idx elem]
        (|do [=array (&&/analyse-1 analyse array-type array)
              =idx (&&/analyse-1 analyse idx-type idx)
              =elem (&&/analyse-1 analyse elem-type elem)
              _ (&type/check exo-type array-type)
              _cursor &/cursor]
          (return (&/|list (&&/|meta exo-type _cursor
                                     (<store-tag> (&/T [=array =idx =elem])))))))
      )

    "java.lang.Boolean"   "[Z" analyse-jvm-znewarray &&/$jvm-znewarray analyse-jvm-zaload &&/$jvm-zaload analyse-jvm-zastore &&/$jvm-zastore
    "java.lang.Byte"      "[B" analyse-jvm-bnewarray &&/$jvm-bnewarray analyse-jvm-baload &&/$jvm-baload analyse-jvm-bastore &&/$jvm-bastore
    "java.lang.Short"     "[S" analyse-jvm-snewarray &&/$jvm-snewarray analyse-jvm-saload &&/$jvm-saload analyse-jvm-sastore &&/$jvm-sastore
    "java.lang.Integer"   "[I" analyse-jvm-inewarray &&/$jvm-inewarray analyse-jvm-iaload &&/$jvm-iaload analyse-jvm-iastore &&/$jvm-iastore
    "java.lang.Long"      "[J" analyse-jvm-lnewarray &&/$jvm-lnewarray analyse-jvm-laload &&/$jvm-laload analyse-jvm-lastore &&/$jvm-lastore
    "java.lang.Float"     "[F" analyse-jvm-fnewarray &&/$jvm-fnewarray analyse-jvm-faload &&/$jvm-faload analyse-jvm-fastore &&/$jvm-fastore
    "java.lang.Double"    "[D" analyse-jvm-dnewarray &&/$jvm-dnewarray analyse-jvm-daload &&/$jvm-daload analyse-jvm-dastore &&/$jvm-dastore
    "java.lang.Character" "[C" analyse-jvm-cnewarray &&/$jvm-cnewarray analyse-jvm-caload &&/$jvm-caload analyse-jvm-castore &&/$jvm-castore
    ))

(defn array-class? [class-name]
  (or (= &host-type/array-data-tag class-name)
      (case class-name
        ("[Z" "[B" "[S" "[I" "[J" "[F" "[D" "[C") true
        ;; else
        false)))

(let [length-type &type/Int
      idx-type &type/Int]
  (defn analyse-jvm-anewarray [analyse exo-type gclass length]
    (|do [gtype-env &/get-type-env
          =gclass (&host-type/instance-gtype &type/existential gtype-env gclass)
          :let [array-type (&/$DataT &host-type/array-data-tag (&/|list =gclass))]
          =length (&&/analyse-1 analyse length-type length)
          _ (&type/check exo-type array-type)
          _cursor &/cursor]
      (return (&/|list (&&/|meta exo-type _cursor
                                 (&&/$jvm-anewarray (&/T [gclass =length gtype-env])))))))

  (defn analyse-jvm-aaload [analyse exo-type array idx]
    (|do [=array (&&/analyse-1+ analyse array)
          [arr-class arr-params] (ensure-object (&&/expr-type* =array))
          _ (&/assert! (= &host-type/array-data-tag arr-class) (str "[Analyser Error] Expected array. Instead got: " arr-class))
          :let [(&/$Cons inner-arr-type (&/$Nil)) arr-params]
          =idx (&&/analyse-1 analyse idx-type idx)
          _ (&type/check exo-type inner-arr-type)
          _cursor &/cursor]
      (return (&/|list (&&/|meta exo-type _cursor
                                 (&&/$jvm-aaload (&/T [=array =idx])))))))

  (defn analyse-jvm-aastore [analyse exo-type array idx elem]
    (|do [=array (&&/analyse-1+ analyse array)
          :let [array-type (&&/expr-type* =array)]
          [arr-class arr-params] (ensure-object array-type)
          _ (&/assert! (= &host-type/array-data-tag arr-class) (str "[Analyser Error] Expected array. Instead got: " arr-class))
          :let [(&/$Cons inner-arr-type (&/$Nil)) arr-params]
          =idx (&&/analyse-1 analyse idx-type idx)
          =elem (&&/analyse-1 analyse inner-arr-type elem)
          _ (&type/check exo-type array-type)
          _cursor &/cursor]
      (return (&/|list (&&/|meta exo-type _cursor
                                 (&&/$jvm-aastore (&/T [=array =idx =elem]))))))))

(defn analyse-jvm-arraylength [analyse exo-type array]
  (|do [=array (&&/analyse-1+ analyse array)
        [arr-class arr-params] (ensure-object (&&/expr-type* =array))
        _ (&/assert! (array-class? arr-class) (str "[Analyser Error] Expected array. Instead got: " arr-class))
        _ (&type/check exo-type &type/Int)
        _cursor &/cursor]
    (return (&/|list (&&/|meta exo-type _cursor
                               (&&/$jvm-arraylength =array)
                               )))))

(defn generic-class->simple-class [gclass]
  "(-> GenericClass Text)"
  (|case gclass
    (&/$GenericTypeVar var-name)
    "java.lang.Object"

    (&/$GenericWildcard _)
    "java.lang.Object"
    
    (&/$GenericClass name params)
    name

    (&/$GenericArray param)
    (|case param
      (&/$GenericArray _)
      (str "[" (generic-class->simple-class param))

      (&/$GenericClass "boolean" _)
      "[Z"
      
      (&/$GenericClass "byte" _)
      "[B"
      
      (&/$GenericClass "short" _)
      "[S"
      
      (&/$GenericClass "int" _)
      "[I"
      
      (&/$GenericClass "long" _)
      "[J"
      
      (&/$GenericClass "float" _)
      "[F"
      
      (&/$GenericClass "double" _)
      "[D"
      
      (&/$GenericClass "char" _)
      "[C"

      (&/$GenericClass name params)
      (str "[L" name ";")

      (&/$GenericTypeVar var-name)
      "[Ljava.lang.Object;"

      (&/$GenericWildcard _)
      "[Ljava.lang.Object;")
    ))

(defn generic-class->type [env gclass]
  "(-> (List (, TypeVar Type)) GenericClass (Lux Type))"
  (|case gclass
    (&/$GenericTypeVar var-name)
    (if-let [ex (&/|get var-name env)]
      (return ex)
      (fail (str "[Analysis Error] Unknown type var: " var-name)))
    
    (&/$GenericClass name params)
    (case name
      "boolean" (return (&/$DataT "java.lang.Boolean" &/$Nil))
      "byte"    (return (&/$DataT "java.lang.Byte" &/$Nil))
      "short"   (return (&/$DataT "java.lang.Short" &/$Nil))
      "int"     (return (&/$DataT "java.lang.Integer" &/$Nil))
      "long"    (return (&/$DataT "java.lang.Long" &/$Nil))
      "float"   (return (&/$DataT "java.lang.Float" &/$Nil))
      "double"  (return (&/$DataT "java.lang.Double" &/$Nil))
      "char"    (return (&/$DataT "java.lang.Character" &/$Nil))
      "void"    (return &/$UnitT)
      ;; else
      (|do [=params (&/map% (partial generic-class->type env) params)]
        (return (&/$DataT name =params))))

    (&/$GenericArray param)
    (|do [=param (generic-class->type env param)]
      (return (&/$DataT &host-type/array-data-tag (&/|list =param))))

    (&/$GenericWildcard _)
    (return (&/$ExQ &/$Nil (&/$BoundT 1)))
    ))

(defn gen-super-env [class-env supers class-decl]
  "(-> (List (, TypeVar Type)) (List SuperClassDecl) ClassDecl (Lux (List (, Text Type))))"
  (|let [[class-name class-vars] class-decl]
    (|case (&/|some (fn [super]
                      (|let [[super-name super-params] super]
                        (if (= class-name super-name)
                          (&/$Some (&/zip2 (&/|map &/|first class-vars) super-params))
                          &/$None)))
                    supers)
      (&/$None)
      (fail (str "[Analyser Error] Unrecognized super-class: " class-name))

      (&/$Some vars+gtypes)
      (&/map% (fn [var+gtype]
                (|do [:let [[var gtype] var+gtype]
                      =gtype (generic-class->type class-env gtype)]
                  (return (&/T [var =gtype]))))
              vars+gtypes)
      )))

(defn ^:private make-type-env [type-params]
  "(-> (List TypeParam) (Lux (List [Text Type])))"
  (&/map% (fn [gvar]
            (|do [:let [[gvar-name _] gvar]
                  ex &type/existential]
              (return (&/T [gvar-name ex]))))
          type-params))

(defn ^:private double-register-gclass? [gclass]
  (|case gclass
    (&/$GenericClass name _)
    (|case name
      "long"   true
      "double" true
      _        false)

    _
    false))

(defn ^:private method-input-folder [full-env]
  (fn [body* input*]
    (|do [:let [[iname itype*] input*]
          itype (generic-class->type full-env itype*)]
      (if (double-register-gclass? itype*)
        (&&env/with-local iname itype
          (&&env/with-local "" &/$VoidT
            body*))
        (&&env/with-local iname itype
          body*)))))

(defn ^:private analyse-method [analyse class-decl class-env all-supers method]
  "(-> Analyser ClassDecl (List (, TypeVar Type)) (List SuperClassDecl) MethodSyntax (Lux MethodAnalysis))"
  (|let [[?cname ?cparams] class-decl
         class-type (&/$DataT ?cname (&/|map &/|second class-env))]
    (|case method
      (&/$ConstructorMethodSyntax =privacy-modifier ?strict ?anns ?gvars ?exceptions ?inputs ?ctor-args ?body)
      (|do [method-env (make-type-env ?gvars)
            :let [full-env (&/|++ class-env method-env)]
            :let [output-type &/$UnitT]
            =ctor-args (&/map% (fn [ctor-arg]
                                 (|do [:let [[ca-type ca-term] ctor-arg]
                                       =ca-type (generic-class->type full-env ca-type)
                                       =ca-term (&&/analyse-1 analyse =ca-type ca-term)]
                                   (return (&/T [ca-type =ca-term]))))
                               ?ctor-args)
            =body (&/with-type-env full-env
                    (&&env/with-local &&/jvm-this class-type
                      (&/fold (method-input-folder full-env)
                              (&&/analyse-1 analyse output-type ?body)
                              (&/|reverse ?inputs))))]
        (return (&/$ConstructorMethodAnalysis (&/T [=privacy-modifier ?strict ?anns ?gvars ?exceptions ?inputs =ctor-args =body]))))
      
      (&/$VirtualMethodSyntax ?name =privacy-modifier =final? ?strict ?anns ?gvars ?exceptions ?inputs ?output ?body)
      (|do [method-env (make-type-env ?gvars)
            :let [full-env (&/|++ class-env method-env)]
            output-type (generic-class->type full-env ?output)
            =body (&/with-type-env full-env
                    (&&env/with-local &&/jvm-this class-type
                      (&/fold (method-input-folder full-env)
                              (&&/analyse-1 analyse output-type ?body)
                              (&/|reverse ?inputs))))]
        (return (&/$VirtualMethodAnalysis (&/T [?name =privacy-modifier =final? ?strict ?anns ?gvars ?exceptions ?inputs ?output =body]))))
      
      (&/$OverridenMethodSyntax ?class-decl ?name ?strict ?anns ?gvars ?exceptions ?inputs ?output ?body)
      (|do [super-env (gen-super-env class-env all-supers ?class-decl)
            method-env (make-type-env ?gvars)
            :let [full-env (&/|++ super-env method-env)]
            output-type (generic-class->type full-env ?output)
            =body (&/with-type-env full-env
                    (&&env/with-local &&/jvm-this class-type
                      (&/fold (method-input-folder full-env)
                              (&&/analyse-1 analyse output-type ?body)
                              (&/|reverse ?inputs))))]
        (return (&/$OverridenMethodAnalysis (&/T [?class-decl ?name ?strict ?anns ?gvars ?exceptions ?inputs ?output =body]))))

      (&/$StaticMethodSyntax ?name =privacy-modifier ?strict ?anns ?gvars ?exceptions ?inputs ?output ?body)
      (|do [method-env (make-type-env ?gvars)
            :let [full-env method-env]
            output-type (generic-class->type full-env ?output)
            =body (&/with-type-env full-env
                    (&/fold (method-input-folder full-env)
                            (&&/analyse-1 analyse output-type ?body)
                            (&/|reverse ?inputs)))]
        (return (&/$StaticMethodAnalysis (&/T [?name =privacy-modifier ?strict ?anns ?gvars ?exceptions ?inputs ?output =body]))))

      (&/$AbstractMethodSyntax ?name =privacy-modifier ?anns ?gvars ?exceptions ?inputs ?output)
      (return (&/$AbstractMethodAnalysis (&/T [?name =privacy-modifier ?anns ?gvars ?exceptions ?inputs ?output])))

      (&/$NativeMethodSyntax ?name =privacy-modifier ?anns ?gvars ?exceptions ?inputs ?output)
      (return (&/$NativeMethodAnalysis (&/T [?name =privacy-modifier ?anns ?gvars ?exceptions ?inputs ?output])))
      )))

(defn ^:private mandatory-methods [supers]
  (|do [class-loader &/loader]
    (&/flat-map% (partial &host/abstract-methods class-loader) supers)))

(defn ^:private check-method-completion [supers methods]
  "(-> (List SuperClassDecl) (List (, MethodDecl Analysis)) (Lux Null))"
  (|do [abstract-methods (mandatory-methods supers)
        :let [methods-map (&/fold (fn [mmap mentry]
                                    (|case mentry
                                      (&/$ConstructorMethodAnalysis _)
                                      mmap
                                      
                                      (&/$VirtualMethodAnalysis _)
                                      mmap
                                      
                                      (&/$OverridenMethodAnalysis =class-decl =name ?strict =anns =gvars =exceptions =inputs =output body)
                                      (update-in mmap [=name] (fn [old-inputs] (if old-inputs (conj old-inputs =inputs) [=inputs])))

                                      (&/$StaticMethodAnalysis _)
                                      mmap

                                      (&/$AbstractMethodSyntax _)
                                      mmap

                                      (&/$NativeMethodSyntax _)
                                      mmap
                                      ))
                                  {}
                                  methods)
              missing-method (&/fold (fn [missing abs-meth]
                                       (or missing
                                           (|let [[am-name am-inputs] abs-meth]
                                             (if-let [meth-struct (get methods-map am-name)]
                                               (if (some (fn [=inputs]
                                                           (and (= (&/|length =inputs) (&/|length am-inputs))
                                                                (&/fold2 (fn [prev mi ai]
                                                                           (|let [[iname itype] mi]
                                                                             (and prev (= (generic-class->simple-class itype) ai))))
                                                                         true
                                                                         =inputs am-inputs)))
                                                         meth-struct)
                                                 nil
                                                 abs-meth)
                                               abs-meth))))
                                     nil
                                     abstract-methods)]]
    (if (nil? missing-method)
      (return nil)
      (|let [[am-name am-inputs] missing-method]
        (fail (str "[Analyser Error] Missing method: " am-name " " "(" (->> am-inputs (&/|interpose " ") (&/fold str "")) ")"))))))

(defn ^:private analyse-field [analyse gtype-env field]
  "(-> Analyser GTypeEnv FieldSyntax (Lux FieldAnalysis))"
  (|case field
    (&/$ConstantFieldSyntax ?name ?anns ?gclass ?value)
    (|do [=gtype (&host-type/instance-gtype &type/existential gtype-env ?gclass)
          =value (&&/analyse-1 analyse =gtype ?value)]
      (return (&/$ConstantFieldAnalysis ?name ?anns ?gclass =value)))
    
    (&/$VariableFieldSyntax ?name ?privacy-modifier ?state-modifier ?anns ?type)
    (return (&/$VariableFieldAnalysis ?name ?privacy-modifier ?state-modifier ?anns ?type))
    ))

(defn analyse-jvm-class [analyse compile-token class-decl super-class interfaces =inheritance-modifier =anns ?fields methods]
  (&/with-closure
    (|do [module &/get-module-name
          :let [[?name ?params] class-decl
                full-name (str (string/replace module "/" ".") "." ?name)
                class-decl* (&/T [full-name ?params])
                all-supers (&/$Cons super-class interfaces)]
          class-env (make-type-env ?params)
          =fields (&/map% (partial analyse-field analyse class-env) ?fields)
          _ (&host/use-dummy-class class-decl super-class interfaces &/$None =fields methods)
          =methods (&/map% (partial analyse-method analyse class-decl* class-env all-supers) methods)
          _ (check-method-completion all-supers =methods)
          _ (compile-token (&&/$jvm-class (&/T [class-decl super-class interfaces =inheritance-modifier =anns =fields =methods &/$Nil &/$None])))
          :let [_ (println 'DEF full-name)]]
      (return &/$Nil))))

(defn analyse-jvm-interface [analyse compile-token interface-decl supers =anns =methods]
  (|do [module &/get-module-name
        _ (compile-token (&&/$jvm-interface (&/T [interface-decl supers =anns =methods])))
        :let [_ (println 'DEF (str module "." (&/|first interface-decl)))]]
    (return &/$Nil)))

(defn ^:private captured-source [env-entry]
  (|case env-entry
    [name [_ (&&/$captured _ _ source)]]
    source))

(let [default-<init> (&/$ConstructorMethodSyntax (&/T [&/$PublicPM
                                                       false
                                                       &/$Nil
                                                       &/$Nil
                                                       &/$Nil
                                                       &/$Nil
                                                       &/$Nil
                                                       (&/$TupleS &/$Nil)]))
      captured-slot-class "java.lang.Object"
      captured-slot-type (&/$GenericClass captured-slot-class &/$Nil)]
  (defn analyse-jvm-anon-class [analyse compile-token exo-type super-class interfaces ctor-args methods]
    (&/with-closure
      (|do [module &/get-module-name
            scope &/get-scope-name
            :let [name (&host/location (&/|tail scope))
                  class-decl (&/T [name &/$Nil])
                  anon-class (str (string/replace module "/" ".") "." name)
                  anon-class-type (&/$DataT anon-class &/$Nil)]
            =ctor-args (&/map% (fn [ctor-arg]
                                 (|let [[arg-type arg-term] ctor-arg]
                                   (|do [=arg-term (&&/analyse-1+ analyse arg-term)]
                                     (return (&/T [arg-type =arg-term])))))
                               ctor-args)
            _ (->> methods
                   (&/$Cons default-<init>)
                   (&host/use-dummy-class class-decl super-class interfaces (&/$Some =ctor-args) &/$Nil))
            :let [all-supers (&/$Cons super-class interfaces)
                  class-env &/$Nil]
            =methods (&/map% (partial analyse-method analyse class-decl class-env all-supers) methods)
            _ (check-method-completion all-supers =methods)
            =captured &&env/captured-vars
            :let [=fields (&/|map (fn [^objects idx+capt]
                                    (|let [[idx _] idx+capt]
                                      (&/$VariableFieldAnalysis (str &c!base/closure-prefix idx)
                                                                &/$PublicPM
                                                                &/$FinalSM
                                                                &/$Nil
                                                                captured-slot-type)))
                                  (&/enumerate =captured))]
            :let [sources (&/|map captured-source =captured)]
            _ (compile-token (&&/$jvm-class (&/T [class-decl super-class interfaces &/$DefaultIM &/$Nil =fields =methods =captured (&/$Some =ctor-args)])))
            _cursor &/cursor]
        (return (&/|list (&&/|meta anon-class-type _cursor
                                   (&&/$jvm-new (&/T [anon-class (&/|repeat (&/|length sources) captured-slot-class) sources]))
                                   )))
        ))))

(defn analyse-jvm-try [analyse exo-type ?body ?catches+?finally]
  (|do [:let [[?catches ?finally] ?catches+?finally]
        =catches (&/map% (fn [_catch_]
                           (|do [:let [[?ex-class ?ex-arg ?catch-body] _catch_]
                                 =catch-body (&&env/with-local ?ex-arg (&/$DataT ?ex-class &/$Nil)
                                               (&&/analyse-1 analyse exo-type ?catch-body))
                                 idx &&env/next-local-idx]
                             (return (&/T [?ex-class idx =catch-body]))))
                         ?catches)
        :let [catched-exceptions (&/|map (fn [=catch]
                                           (|let [[_c-class _ _] =catch]
                                             _c-class))
                                         =catches)]
        =body (with-catches catched-exceptions
                (&&/analyse-1 analyse exo-type ?body))
        =finally (|case ?finally
                   (&/$None)           (return &/$None)
                   (&/$Some ?finally*) (|do [=finally (&&/analyse-1+ analyse ?finally*)]
                                         (return (&/$Some =finally))))
        _cursor &/cursor]
    (return (&/|list (&&/|meta exo-type _cursor
                               (&&/$jvm-try (&/T [=body =catches =finally])))))))

(defn analyse-jvm-throw [analyse exo-type ?ex]
  (|do [=ex (&&/analyse-1 analyse (&/$DataT "java.lang.Throwable" &/$Nil) ?ex)
        _cursor &/cursor
        _ (&type/check exo-type &/$VoidT)]
    (return (&/|list (&&/|meta exo-type _cursor (&&/$jvm-throw =ex))))))

(do-template [<name> <tag>]
  (defn <name> [analyse exo-type ?monitor]
    (|do [=monitor (&&/analyse-1+ analyse ?monitor)
          _ (ensure-object (&&/expr-type* =monitor))
          :let [output-type &/$UnitT]
          _ (&type/check exo-type output-type)
          _cursor &/cursor]
      (return (&/|list (&&/|meta output-type _cursor (<tag> =monitor))))))

  analyse-jvm-monitorenter &&/$jvm-monitorenter
  analyse-jvm-monitorexit  &&/$jvm-monitorexit
  )

(do-template [<name> <tag> <from-class> <to-class>]
  (let [output-type (&/$DataT <to-class> &/$Nil)]
    (defn <name> [analyse exo-type ?value]
      (|do [=value (&&/analyse-1 analyse (&/$DataT <from-class> &/$Nil) ?value)
            _ (&type/check exo-type output-type)
            _cursor &/cursor]
        (return (&/|list (&&/|meta output-type _cursor (<tag> =value)))))))

  analyse-jvm-d2f &&/$jvm-d2f "java.lang.Double"    "java.lang.Float"
  analyse-jvm-d2i &&/$jvm-d2i "java.lang.Double"    "java.lang.Integer"
  analyse-jvm-d2l &&/$jvm-d2l "java.lang.Double"    "java.lang.Long"

  analyse-jvm-f2d &&/$jvm-f2d "java.lang.Float"     "java.lang.Double"
  analyse-jvm-f2i &&/$jvm-f2i "java.lang.Float"     "java.lang.Integer"
  analyse-jvm-f2l &&/$jvm-f2l "java.lang.Float"     "java.lang.Long"

  analyse-jvm-i2b &&/$jvm-i2b "java.lang.Integer"   "java.lang.Byte"
  analyse-jvm-i2c &&/$jvm-i2c "java.lang.Integer"   "java.lang.Character"
  analyse-jvm-i2d &&/$jvm-i2d "java.lang.Integer"   "java.lang.Double"
  analyse-jvm-i2f &&/$jvm-i2f "java.lang.Integer"   "java.lang.Float"
  analyse-jvm-i2l &&/$jvm-i2l "java.lang.Integer"   "java.lang.Long"
  analyse-jvm-i2s &&/$jvm-i2s "java.lang.Integer"   "java.lang.Short"

  analyse-jvm-l2d &&/$jvm-l2d "java.lang.Long"      "java.lang.Double"
  analyse-jvm-l2f &&/$jvm-l2f "java.lang.Long"      "java.lang.Float"
  analyse-jvm-l2i &&/$jvm-l2i "java.lang.Long"      "java.lang.Integer"

  analyse-jvm-c2b &&/$jvm-c2b "java.lang.Character" "java.lang.Byte"
  analyse-jvm-c2s &&/$jvm-c2s "java.lang.Character" "java.lang.Short"
  analyse-jvm-c2i &&/$jvm-c2i "java.lang.Character" "java.lang.Integer"
  analyse-jvm-c2l &&/$jvm-c2l "java.lang.Character" "java.lang.Long"
  )

(do-template [<name> <tag> <from-class> <to-class>]
  (let [output-type (&/$DataT <to-class> &/$Nil)]
    (defn <name> [analyse exo-type ?value]
      (|do [=value (&&/analyse-1 analyse (&/$DataT <from-class> &/$Nil) ?value)
            _ (&type/check exo-type output-type)
            _cursor &/cursor]
        (return (&/|list (&&/|meta output-type _cursor (<tag> =value)))))))

  analyse-jvm-iand  &&/$jvm-iand  "java.lang.Integer" "java.lang.Integer"
  analyse-jvm-ior   &&/$jvm-ior   "java.lang.Integer" "java.lang.Integer"
  analyse-jvm-ixor  &&/$jvm-ixor  "java.lang.Integer" "java.lang.Integer"
  analyse-jvm-ishl  &&/$jvm-ishl  "java.lang.Integer" "java.lang.Integer"
  analyse-jvm-ishr  &&/$jvm-ishr  "java.lang.Integer" "java.lang.Integer"
  analyse-jvm-iushr &&/$jvm-iushr "java.lang.Integer" "java.lang.Integer"

  analyse-jvm-land  &&/$jvm-land  "java.lang.Long"    "java.lang.Long"
  analyse-jvm-lor   &&/$jvm-lor   "java.lang.Long"    "java.lang.Long"
  analyse-jvm-lxor  &&/$jvm-lxor  "java.lang.Long"    "java.lang.Long"
  analyse-jvm-lshl  &&/$jvm-lshl  "java.lang.Long"    "java.lang.Integer"
  analyse-jvm-lshr  &&/$jvm-lshr  "java.lang.Long"    "java.lang.Integer"
  analyse-jvm-lushr &&/$jvm-lushr "java.lang.Long"    "java.lang.Integer"
  )

(let [input-type (&/$AppT &type/List &type/Text)
      output-type (&/$AppT &type/IO &/$UnitT)]
  (defn analyse-jvm-program [analyse compile-token ?args ?body]
    (|do [=body (&/with-scope ""
                  (&&env/with-local ?args input-type
                    (&&/analyse-1 analyse output-type ?body)))
          _ (compile-token (&&/$jvm-program =body))]
      (return &/$Nil))))

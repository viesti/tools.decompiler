;;   Copyright (c) Nicola Mometto & contributors.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.decompiler.ast
  (:require [clojure.set :as set]
            [clojure.tools.decompiler.stack :refer [peek-n pop-n]]
            [clojure.tools.decompiler.bc :as bc]
            [clojure.tools.decompiler.utils :as u]))

;; WIP casting, type hints

(def initial-ctx {:fields {}
                  :statements []
                  :ast {}})

(def initial-local-ctx {:stack []
                        :pc 0
                        :local-variable-table #{}})

;; process-* : bc, ctx -> ctx
;; decompile-* : bc, ctx -> AST

(defmulti process-insn
  (fn [ctx {:insn/keys [name]}] (keyword name))
  :hierarchy #'bc/insn-h)

(defmethod process-insn :default [ctx {:insn/keys [name]}]
  (println "INSN NOT HANDLED:" name)
  ctx)

(defn process-insns [{:keys [stack pc jump-table terminate?] :as ctx :or {terminate? (constantly false)}} bc]
  (if (or (not (get jump-table pc))
          (terminate? ctx))
    ctx
    (let [insn-n (get jump-table pc)
          {:insn/keys [length] :as insn} (nth bc insn-n)]
      (-> (process-insn ctx insn)
          (update :pc (fn [new-pc]
                        (if (= new-pc pc)
                          ;; last insn wasn't an explicit jump, goto next insn
                          (+ new-pc length)
                          new-pc)))
          (recur bc)))))

(defmethod process-insn :return [ctx _]
  ctx)

(defmethod process-insn :nop [ctx _]
  ctx)

(defmethod process-insn ::bc/const-insn [ctx {:insn/keys [pool-element]}]
  (-> ctx
      (update :stack conj {:op :const
                           :val (:insn/target-value pool-element)})))

(defmethod process-insn :swap [{:keys [stack] :as ctx} _]
  (let [[v2 v1] (peek-n stack 2)]
    (-> ctx
        (update :stack pop-n 2)
        (update :stack conj v1 v2))))

(defmethod process-insn :dup_x1 [{:keys [stack] :as ctx} _]
  (let [[v2 v1] (peek-n stack 2)]
    (-> ctx
        (update :stack pop-n 2)
        (update :stack conj v1 v2 v1))))

(defmethod process-insn :dup [{:keys [stack] :as ctx} _]
  (let [val (peek stack)]
    (-> ctx
        (update :stack conj val))))

(defmethod process-insn :anewarray [{:keys [stack] :as ctx} {:insn/keys [pool-element]}]
  (let [{:insn/keys [target-type]} pool-element
        {dimension :val} (peek stack)
        expr {:op :array
              :!items (atom (vec (repeat dimension {:op :const :val nil})))}]

    (-> ctx
        (update :stack pop)
        (update :stack conj expr))))

(defmethod process-insn ::bc/array-store [{:keys [stack] :as ctx} {:insn/keys [pool-element]}]
  (let [[{:keys [!items] :as array} {index :val} value] (peek-n stack 3)]
    (swap! !items assoc index value)
    (-> ctx
        (update :stack pop-n 3))))

(defmethod process-insn :monitorenter [{:keys [stack] :as ctx} _]
  (let [sentinel (peek stack)]
    (-> ctx
        (update :stack pop)
        (update :stack conj {:op :monitor-enter
                             :sentinel sentinel}))))

(defmethod process-insn :monitorexit [{:keys [stack] :as ctx} _]
  (let [sentinel (peek stack)]
    (-> ctx
        (update :stack pop)
        (update :stack conj {:op :monitor-exit
                             :sentinel sentinel}))))

(defn ->do [exprs]
  (if (empty? (second exprs))
    (first exprs)
    {:op :do
     :statements (vec (butlast exprs))
     :ret (or (last exprs) {:op :const :val nil})}))

(defmethod process-insn ::bc/return-value [{:keys [stack statements] :as ctx} _]
  (let [ret (peek stack)]
    (-> ctx
        (assoc :stack [] :statements []
               :ast (->do (conj statements ret))))))

(defn pc= [terminate-at]
  (fn [{:keys [pc]}]
    (= pc terminate-at)))

(defn process-if [{:keys [insns jump-table stack] :as ctx} test [start-then end-then] [start-else end-else]]
  (let [{then-stack :stack then-stmnts :statements} (process-insns (assoc ctx :pc start-then :terminate? (pc= end-then) :statements []) insns)
        {else-stack :stack else-stmnts :statements} (process-insns (assoc ctx :pc start-else :terminate? (pc= end-else) :statements []) insns)

        statement? (= stack then-stack else-stack)

        [then else] (if statement?
                      [then-stmnts else-stmnts]
                      [(conj then-stmnts (peek then-stack))
                       (conj else-stmnts (peek else-stack))])]

    (-> ctx
        (assoc :pc end-else)
        (update (if statement? :statements :stack)
                conj {:op :if
                      :test test
                      :then (->do then)
                      :else (->do else)}))))

(defn goto-label [{:insn/keys [jump-offset label]}]
  (+ jump-offset label))

(defmethod process-insn :ifnull [{:keys [stack jump-table insns] :as ctx} {:insn/keys [label] :as insn}]
  (let [null-label (goto-label insn)

        goto-end-insn (nth insns (-> (get jump-table null-label) (- 1)))
        end-label (goto-label goto-end-insn)

        goto-else-insn (nth insns (-> (get jump-table label) (+ 2)))
        else-label (goto-label goto-else-insn)

        {then-label :insn/label} (nth insns (-> (get jump-table label) (+ 3)))

        [test _] (peek-n stack 2)]

    (-> ctx
        (update :stack pop-n 2)
        (process-if test [then-label (:insn/label goto-end-insn)] [else-label end-label]))))

(defmethod process-insn :ifeq [{:keys [stack jump-table insns] :as ctx} {:insn/keys [label] :as insn}]
  (let [else-label (goto-label insn)

        goto-end-insn (nth insns (-> (get jump-table else-label) (- 2)))
        end-label (goto-label goto-end-insn)

        {then-label :insn/label} (nth insns (-> (get jump-table label) (+ 1)))

        test (peek stack)]

    (-> ctx
        (update :stack pop)
        (process-if test [then-label (:insn/label goto-end-insn)] [else-label end-label]))))

(defmethod process-insn ::bc/number-compare [{:keys [stack jump-table insns] :as ctx} {:insn/keys [label] :as insn}]
  (let [offset (if (= "if_icmpne" (:insn/name insn)) 0 1)
        insn (nth insns (-> (get jump-table label) (+ offset)))

        op (case (:insn/name insn)
             "ifle" ">"
             "ifge" "<"
             "ifne" "="
             "iflt" ">="
             "ifgt" "<="
             "if_icmpne" "=")

        else-label (goto-label insn)

        goto-end-insn (nth insns (-> (get jump-table else-label) (- 2)))
        end-label (goto-label goto-end-insn)

        {then-label :insn/label} (nth insns (-> (get jump-table label) (+ offset 1)))

        [a b] (peek-n stack 2)

        test {:op :invoke :fn {:op :var :ns "clojure.core" :name op} :args [a b]}]

    (-> ctx
        (update :stack pop-n 2)
        (process-if test [then-label (:insn/label goto-end-insn)] [else-label end-label]))))

(defn find-local-variable [{:keys [local-variable-table]} index label]
  (->> local-variable-table
       (filter (comp #{index} :index))
       (filter (comp (partial >= label) :start-label))
       (filter (comp (partial < label) :end-label))
       (sort-by :start-label)
       (first)))

(defn find-init-local [{:keys [local-variable-table]} label]
  (->> local-variable-table
       (filter (comp (partial = label) :start-label))
       ;; why is this here?
       (filter (comp (partial < label) :end-label))
       (sort-by :start-label)
       (first)))

(defmethod process-insn :goto [{:keys [stack loop-args] :as ctx} insn]
  (let [jump-label (goto-label insn)
        args (for [{:keys [start-label index]} loop-args
                   :let [{:keys [init]} (find-local-variable ctx index start-label)]]
               init)]
    (-> ctx
        (update :stack conj {:op :recur
                             :args (vec args)}))))

(defmethod process-insn ::bc/load-insn [ctx {:insn/keys [local-variable-element label]}]
  (let [{:insn/keys [target-index]} local-variable-element]
    (if-let [local (find-local-variable ctx target-index label)]
      (-> ctx
          (update :stack conj local))
      (-> ctx
          (update :stack conj {:op :local
                               :name (str "arg_" label)})))))

(defn init-local-variable? [{:insn/keys [label length]} {:keys [start-label]}]
  (= (+ label length) start-label))

(defn find-recur-jump-label [{:keys [jump-table pc insns] :as ctx} {:keys [start-label end-label index]}]
  (loop [[{:insn/keys [name length label local-variable-element] :as insn} & insns] (drop (inc (get jump-table pc)) insns)]

    (cond

      (or (nil? insn)
          (> label end-label))
      false

      (and (isa? bc/insn-h (keyword name) ::bc/store-insn)
           (= (:insn/target-index local-variable-element) index)
           (= (:start-label (find-local-variable ctx index label)) start-label)
           (= "goto" (:insn/name (first insns)))
           (< (goto-label (first insns)) end-label))
      (+ label length)

      :else
      (recur insns))))

(defn find-loop-info [{:keys [insns jump-table local-variable-table] :as ctx}
                      {:keys [start-label index end-label] :as insn}]
  (when-let [jump-label (find-recur-jump-label ctx insn)]
    (let [insn (nth insns (get jump-table jump-label))]
      {:loop-label (goto-label insn)
       :loop-args (->> (for [local-variable local-variable-table
                             :when (and (= (:end-label local-variable) end-label)
                                        (>= (:start-label local-variable) start-label))]
                         local-variable)
                       (sort-by :start-label)
                       (into []))})))

(defn process-loop [{:keys [insns stack jump-table pc] :as ctx} {:keys [loop-label loop-args]} {:keys [end-label] :as local-variable} init]
  (let [{:insn/keys [length]} (nth insns (get jump-table pc))]
    (loop [[arg & loop-args] (rest loop-args)
           args-ctx (-> ctx (update :pc + length))
           args [{:op :local-variable :local-variable local-variable :init init}]]
      (if arg
        (let [pre-insn (nth insns (dec (get jump-table (:start-label arg)))) ;; astore
              {:keys [local-variable-table statements stack] :as new-ctx} (process-insns (-> args-ctx
                                                                                             (assoc :terminate? (pc= (:insn/label pre-insn)))
                                                                                             (assoc :statements []))
                                                                                         insns)

              local-variable (find-init-local new-ctx (:start-label arg))
              init (if (seq statements) (->do (conj statements (peek stack))) (peek stack))]
          (recur loop-args
                 (-> new-ctx
                     (update :pc + (:insn/length pre-insn))
                     (update :local-variable-table disj local-variable)
                     (update :local-variable-table conj (assoc local-variable :init init)))
                 (conj args {:op :local-variable :local-variable local-variable :init init})))

        (let [{body-stack :stack body-stmnts :statements} (process-insns (-> ctx
                                                                             (assoc :local-variable (:local-variable args-ctx))
                                                                             (assoc :pc loop-label)
                                                                             (assoc :loop-args (mapv :local-variable args))
                                                                             (assoc :terminate? (pc= end-label))
                                                                             (assoc :statements []))
                                                                         insns)
              statement? (not= "areturn" (:insn/name (nth insns (get jump-table end-label))))
              ;; WIP if statement is peek body stack correct?
              body (->do (conj body-stmnts (peek body-stack)))]
          (-> ctx
              (assoc :pc end-label)
              (update (if statement? :statements :stack)
                      conj {:op :loop
                            :local-variables args
                            :body body})))))))

(defn process-let [{:keys [insns stack jump-table pc] :as ctx} {:keys [end-label] :as local-variable} init]
  (let [{:insn/keys [length]} (nth insns (get jump-table pc))
        {body-stack :stack body-stmnts :statements} (process-insns (-> ctx
                                                                       (update :pc + length)
                                                                       (assoc :terminate? (pc= end-label))
                                                                       (assoc :statements []))
                                                                   insns)
        statement? (= stack body-stack)
        body (->do (if statement? body-stmnts (conj body-stmnts (peek body-stack))))]
    (-> ctx
        (assoc :pc end-label)
        (update (if statement? :statements :stack)
                conj {:op :let
                      :local-variable {:op :local-variable
                                       :local-variable local-variable
                                       :init init}
                      :body body}))))

;; WIP letfn
(defn process-lexical-block [ctx local-variable init]
  (if-let [loop-info (find-loop-info ctx local-variable)]
    (process-loop ctx loop-info local-variable init)
    (process-let ctx local-variable init)))

(defmethod process-insn :instanceof [{:keys [stack] :as ctx} {:insn/keys [pool-element]}]
  (let [{:insn/keys [target-type]} pool-element
        instance (peek stack)]
    (-> ctx
        (update :stack pop)
        (update :stack conj {:op :invoke
                             :fn {:op :var
                                  :ns "clojure.core"
                                  :name "instance?"}
                             :args [{:op :const
                                     :val (symbol target-type)}
                                    instance]}))))

(defmethod process-insn :pop [{:keys [stack] :as ctx} {:insn/keys [label length]}]
  (let [statement (peek stack)
        ctx (-> ctx (update :stack pop))]
    (if-let [local-variable (find-init-local ctx (+ label length))]
      (-> ctx
          (update :local-variable-table disj local-variable)
          (update :local-variable-table conj (assoc local-variable :init statement))
          (process-lexical-block local-variable statement))
      (-> ctx
          (update :statements conj statement)))))

(defmethod process-insn ::bc/store-insn [{:keys [stack insns jump-table] :as ctx}
                                         {:insn/keys [local-variable-element label length] :as insn}]
  (let [{:insn/keys [target-index]} local-variable-element
        {:keys [start-label end-label] :as local-variable} (find-local-variable ctx target-index (+ label length))
        init (peek stack)
        initialized-local-variable (assoc local-variable :init init)
        ctx (-> ctx
                (update :stack pop)
                (update :local-variable-table disj local-variable)
                (update :local-variable-table conj initialized-local-variable))]
    (if (init-local-variable? insn local-variable)
      (process-lexical-block ctx local-variable init)
      ctx)))

;; WIP new on :new rather than invokespecial
(defmethod process-insn :new [ctx _]
  ctx)

(defmethod process-insn :invokespecial [{:keys [stack] :as ctx} {:insn/keys [pool-element]}]
  (let [{:insn/keys [target-class target-arg-types]} pool-element
        argc (count target-arg-types)
        args (peek-n stack argc)]
    (-> ctx
        (update :stack pop-n (inc argc))
        (update :stack conj {:op :new
                             :class target-class
                             :args args}))))

(defmethod process-insn :athrow [{:keys [stack] :as ctx} _]
  (let [ex (peek stack)]
    (-> ctx
        (update :stack pop)
        (update :statements conj {:op :throw
                                  :ex ex}))))


(defmethod process-insn ::bc/invoke-instance-method [{:keys [stack] :as ctx} {:insn/keys [pool-element]}]
  (let [{:insn/keys [target-class target-name target-arg-types]} pool-element
        argc (count (conj target-arg-types target-class))
        [target & args] (peek-n stack argc)]
    (-> ctx
        (update :stack pop-n argc)
        (update :stack conj {:op :invoke-instance
                             :method target-name
                             :target target
                             :arg-types target-arg-types
                             :target-class target-class
                             :args args}))))

(defmethod process-insn :putstatic [{:keys [stack class-name] :as ctx} {:insn/keys [pool-element]}]
  (let [{:insn/keys [target-class target-name]} pool-element
        val (peek stack)
        ctx (update ctx :stack pop)]
    (if (= class-name target-class)
      (-> ctx
          (update :fields assoc target-name val))
      (-> ctx
          (update :statements conj {:op :set!
                                    :target {:op :static-field
                                             :target target-class
                                             :field target-name}
                                    :val val})))))

(defn process-keyword-invoke [{:keys [insns jump-table pc fields] :as ctx} {:insn/keys [length pool-element]}]
  (let [{:insn/keys [target-name]} pool-element
        {:keys [pc statements stack]} (process-insns (assoc ctx
                                                            :pc (->> (get jump-table pc) (+ 2) (nth insns) :insn/label)
                                                            :terminate? (fn [{:keys [pc jump-table insns]}]
                                                                          (->> (get jump-table pc)
                                                                               (nth insns)
                                                                               :insn/name
                                                                               (= "dup_x2")))
                                                            :statements [])
                                                     insns)
        target (->do (conj statements (peek stack)))]
    (-> ctx
        (assoc :pc (+ pc 36)) ;; why bother writing robust code when we can just hardcode bytecode offsets
        (update :stack conj {:op :invoke
                             :fn (-> (get-in fields [target-name :args 0 :args 1])
                                     (update :val keyword))
                             :args [target]}))))

(defmethod process-insn :getstatic [{:keys [fields class-name] :as ctx} {:insn/keys [pool-element] :as insn}]
  (let [{:insn/keys [target-class target-name target-type]} pool-element]
    (cond

      (and (= target-type "clojure.lang.ILookupThunk")
           (= target-class class-name))
      (process-keyword-invoke ctx insn)

      (= target-class class-name)
      (update ctx :stack conj (get fields target-name))

      :else
      (update ctx :stack conj {:op :static-field
                               :target target-class
                               :field target-name}))))

(defmethod process-insn :putfield [{:keys [fields class-name stack] :as ctx} {:insn/keys [pool-element]}]
  (let [{:insn/keys [target-class target-name]} pool-element
        [instance val] (peek-n 2 stack)
        ctx (update ctx :stack pop-n 2)]
    (update :statements conj {:op :set!
                              :target (if (= target-class class-name)
                                        {:op :local
                                         :name target-name}
                                        {:op :instance-field
                                         :instance instance
                                         :field target-name})
                              :val val})))

(defmethod process-insn :getfield [{:keys [fields class-name stack] :as ctx} {:insn/keys [pool-element]}]
  (let [{:insn/keys [target-class target-name]} pool-element
        instance (peek stack)
        ctx (update ctx :stack pop)]
    (if (= target-class class-name)
      (update ctx :stack conj (get fields target-name {:op :local :name target-name}))
      (update ctx :stack conj {:op :instance-field
                               :instance instance
                               :field target-name}))))

(defmethod process-insn :invokestatic [{:keys [stack] :as ctx} {:insn/keys [pool-element]}]
  (let [{:insn/keys [target-class target-name target-arg-types]} pool-element
        argc (count target-arg-types)
        args (peek-n stack argc)]
    (-> ctx
        (update :stack pop-n argc)
        (update :stack conj {:op :invoke-static
                             :target target-class
                             :method target-name
                             :arg-types target-arg-types
                             :args args}))))

(defmethod process-insn ::bc/math-insn [{:keys [stack] :as ctx} {:insn/keys [name]}]
  (let [argc (if (#{"dneg" "lneg"} name) 1 2)
        args (peek-n stack argc)
        op  ({"dadd" "+"
              "ddiv" "/"
              "dmul" "*"
              "dsub" "-"
              "iadd" "+"
              "iand" "bit-and"
              "idiv" "/"
              "imul" "*"
              "irem" "rem"
              "ishl" "bit-shift-left"
              "ishr" "bit-shift-right"
              "isub" "-"
              "iushr" "unsigned-bit-shift-right"
              "ladd" "+"
              "land" "bit-and"
              "ldiv" "quot"
              "lmul" "*"
              "lor" "bit-or"
              "lrem" "rem"
              "lshl" "bit-shift-left"
              "lshr" "bit-shift-right"
              "lsub" "-"
              "lushr" "unsigned-bit-shift-right"
              "lxor" "bit-xor"} name)]
    (-> ctx
        (update :stack pop-n argc)
        (update :stack conj {:op :invoke
                             :fn {:op :var
                                  :ns "clojure.core"
                                  :name op}
                             :args args}))))

(defmethod process-insn :checkcast [{:keys [stack] :as ctx} {:insn/keys [pool-element]}]
  (let [{:insn/keys [target-type]} pool-element
        target (peek stack)]
    (-> ctx
        (update :stack pop)
        (update :stack conj (assoc target :cast target-type)))))

(defn merge-local-variable-table [ctx local-variable-table]
  (let [lvt (->> (for [{:local-variable/keys [name index start-label end-label]} local-variable-table]
                   {:op :local
                    :start-label start-label
                    :end-label end-label
                    :index index
                    :name name})
                 (into #{}))]
    (-> ctx
        (assoc :local-variable-table lvt)
        (assoc :loop-args (->> lvt
                               (filter (comp zero? :start-label))
                               (sort-by :index)
                               (vec))))))

(defn process-method-insns [{:keys [fn-name] :as ctx} {:method/keys [bytecode jump-table local-variable-table flags]}]
  (let [ctx (-> ctx
                (merge initial-local-ctx {:jump-table jump-table})
                (merge-local-variable-table local-variable-table)
                (cond-> (not (:static flags))
                  (-> (update :local-variable-table conj {:op :local
                                                          :this? true
                                                          :index 0
                                                          :name fn-name
                                                          :start-label 0
                                                          :end-label (-> bytecode last :insn/label)})
                      (update :loop-args #(vec (rest %)))))
                (assoc :insns bytecode)
                (process-insns bytecode))]
    (apply dissoc ctx :jump-table (keys initial-local-ctx))))

(defn process-static-init [ctx {:class/keys [methods] :as bc}]
  (let [method (u/find-method methods {:method/name "<clinit>"})]
    (process-method-insns ctx method)))

(defn process-init [ctx {:class/keys [methods] :as bc}]
  (let [method (u/find-method methods {:method/name "<init>"})]
    (process-method-insns ctx method)))

(defn decompile-fn-method [ctx {:method/keys [return-type local-variable-table] :as method}]
  (let [{:keys [ast]} (process-method-insns ctx method)]
    {:op :fn-method
     :args (for [{:local-variable/keys [name type start-label]} (->> local-variable-table
                                                                     (sort-by :local-variable/index))
                 :when (= start-label 0)]
             {:name name
              :type type})
     :body ast}))

(defn decompile-fn-methods [ctx {:class/keys [methods] :as bc}]
  (let [invokes (u/find-methods methods {:method/name "invoke"})
        invokes-static (u/find-methods methods {:method/name "invokeStatic"})
        invoke-methods (into invokes-static (for [{:method/keys [arg-types] :as invoke} invokes
                                                  :let [argc (count arg-types)]
                                                  :when (empty? (filter (fn [{:method/keys [arg-types]}]
                                                                          (= (count arg-types) argc))
                                                                        invokes-static))]
                                              invoke))
        methods-asts (mapv (partial decompile-fn-method ctx) invoke-methods)]
    {:op :fn
     :fn-methods methods-asts}))

(defn decompile-fn [{class-name :class/name
                     :class/keys [methods] :as bc}
                    ctx]
  (let [[ns fn-name] ((juxt namespace name) (u/demunge class-name))
        ast (-> ctx
                (assoc :fn-name fn-name)
                (assoc :class-name class-name)
                (process-static-init bc)
                (process-init bc)
                (decompile-fn-methods bc))]
    {:ns ns
     :fn-name fn-name
     :ast ast}))

(defn bc->ast [{:class/keys [super] :as bc}]
  (if (#{"clojure.lang.AFunction"} super)
    (decompile-fn bc initial-ctx)
    (throw (Exception. ":("))))

(comment
  (require '[clojure.tools.decompiler.bc :as bc]
           '[clojure.java.io :as io])

  (def filename (-> "test$foo.class" io/resource .getFile))
  (def bc (bc/analyze-classfile filename))

  (bc->ast bc)

  (fn* ([] "yoo"))

  )

;;; in-ns/def/letfn/case/deftype/reify, varargs, genclass, geninterface, proxy, protocol inline caches

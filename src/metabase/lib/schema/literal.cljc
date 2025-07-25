(ns metabase.lib.schema.literal
  "Malli schemas for string, temporal, number, and boolean literals."
  (:require
   #?@(:clj
       ([java-time.api :as t]))
   #?@(:clj ([metabase.lib.schema.literal.jvm]))
   [metabase.lib.schema.common :as common]
   [metabase.lib.schema.expression :as expression]
   [metabase.lib.schema.mbql-clause :as mbql-clause]
   [metabase.lib.schema.temporal-bucketing :as temporal-bucketing]
   [metabase.util.malli.registry :as mr]
   [metabase.util.number :as u.number]
   [metabase.util.time.impl-common :as u.time.impl-common]))

#?(:clj
   (comment metabase.lib.schema.literal.jvm/keep-me))

(defmethod expression/type-of-method :dispatch-type/nil
  [_nil]
  :type/*)

(defmethod expression/type-of-method :dispatch-type/boolean
  [_bool]
  :type/Boolean)

(mr/def ::integer
  [:or :int [:fn u.number/bigint?]])

(defmethod expression/type-of-method :dispatch-type/integer
  [x]
  (if (u.number/bigint? x) :type/BigInteger :type/Integer))

;;; we should probably also restrict this to disallow NaN and positive/negative infinity, I don't know in what
;;; universe we'd want to allow those if they're not disallowed already.
(mr/def ::non-integer-real
  #?(:clj [:or
           :double
           :metabase.lib.schema.literal.jvm/float
           :metabase.lib.schema.literal.jvm/big-decimal]
     :cljs :double))

(defmethod expression/type-of-method :dispatch-type/number
  [_non-integer-real]
  ;; `:type/Float` is the 'base type' of all non-integer real number types in [[metabase.types.core]] =(
  :type/Float)

;;; TODO -- these temporal literals could be a little stricter, right now they are pretty permissive, you shouldn't be
;;; allowed to have month `13` or `02-29` for example
(mr/def ::string.date
  [:re
   {:error/message "date string literal"}
   u.time.impl-common/local-date-regex])

(mr/def ::string.zone-offset
  [:re
   {:error/message "timezone offset string literal"}
   u.time.impl-common/zone-offset-part-regex])

(mr/def ::string.time
  [:or
   [:re
    {:error/message "local time string literal"}
    u.time.impl-common/local-time-regex]
   [:re
    {:error/message "offset time string literal"}
    u.time.impl-common/offset-time-regex]])

(mr/def ::string.datetime
  [:or
   [:re
    {:error/message "local date time string literal"}
    u.time.impl-common/local-datetime-regex]
   [:re
    {:error/message "offset date time string literal"}
    u.time.impl-common/offset-datetime-regex]])

(defmethod expression/type-of-method :dispatch-type/string
  [s]
  (condp mr/validate s
    ::string.datetime #{:type/Text :type/DateTime}
    ::string.date     #{:type/Text :type/Date}
    ::string.time     #{:type/Text :type/Time}
    :type/Text))

(mr/def ::date
  #?(:clj  [:or
            [:time/local-date
             {:error/message    "instance of java.time.LocalDate"
              :encode/serialize str}]
            ::string.date]
     :cljs ::string.date))

(mr/def ::time
  #?(:clj [:or
           {:doc/title "time literal"}
           ::string.time
           [:time/local-time
            {:error/message    "instance of java.time.LocalTime"
             :encode/serialize str}]
           [:time/offset-time
            {:error/message    "instance of java.time.OffsetTime"
             :encode/serialize str}]]
     :cljs ::string.time))

(mr/def ::datetime
  #?(:clj [:or
           ::string.datetime
           [:time/local-date-time
            {:error/message    "instance of java.time.LocalDateTime"
             :encode/serialize str}]
           [:time/offset-date-time
            {:error/message    "instance of java.time.OffsetDateTime"
             :encode/serialize str}]
           [:time/zoned-date-time
            {:error/message    "instance of java.time.ZonedDateTime"
             :encode/serialize #(str (t/offset-date-time %))}]]
     :cljs ::string.datetime))

(mr/def ::temporal
  [:or
   ::date
   ::time
   ::datetime])

;;; these are currently only allowed inside `:absolute-datetime`

(mr/def ::string.year-month
  [:re
   {:error/message "year-month string literal"}
   u.time.impl-common/year-month-regex])

(mr/def ::string.year
  [:re
   {:error/message "year string literal"}
   u.time.impl-common/year-regex])

;;; `:effective-type` is required for `:value` clauses. This was not a rule in the legacy MBQL schema, but in actual
;;; usage they basically always have `:base-type`; in MLv2 we're trying to use `:effective-type` everywhere instead;
;;; These clauses are useless/pointless without type information anyway, so let's enforce this rule going forward.
;;; Conversion can take care of `:base-type` <=> `:effective-type` as needed.
(mr/def ::value.options
  [:merge
   [:ref ::common/options]
   [:map
    [:effective-type ::common/base-type]
    [:unit {:optional true} [:maybe ::temporal-bucketing/unit]]]])

;;; [:value <opts> <value>] clauses are mostly used internally by the query processor to add type information to
;;; literals, to make it easier for drivers to process queries; see
;;; the [[metabase.query-processor.middleware.wrap-value-literals]] middleware. It is also used to differentiate `nil`
;;; (as in no clause or value) from something intended to be `NULL` in a compiled query, and to associate type
;;; information with that `nil`. Even if this is mostly used internally, the schema still needs to know about it.
;;;
;;; The schema itself does not currently enforce that the actual <value> matches up with the `:effective-type` in the
;;; options map; this is only enforced in the QP. For now, it assumes you know what you are doing and takes your word
;;; for it when you say something has a given `:effective-type`.
(mbql-clause/define-mbql-clause :value
  [:tuple
   {:error/message "Value :value clause"}
   #_tag   [:= {:decode/normalize common/normalize-keyword} :value]
   #_opts  [:ref ::value.options]
   #_value any?])

(mr/def ::literal
  [:or
   :nil
   :boolean
   :string
   ::integer
   ::non-integer-real
   ::temporal])

(ns hive-mcp.tools.catchup.outcome
  (:require [malli.core :as m]))

(def Status [:enum :ok :degraded :timeout :error])

(def Warning
  [:map
   [:label :string]
   [:status [:enum :degraded :timeout :error]]
   [:message {:optional true} :string]])

(def QueryOutcome
  [:map
   [:status Status]
   [:value {:optional true} :any]
   [:warnings [:vector Warning]]])

(defn ok [value]
  {:status :ok :value value :warnings []})

(defn degraded [value warnings]
  {:status :degraded :value value :warnings (vec warnings)})

(defn failure [status label message]
  {:status status
   :warnings [(cond-> {:status status :label label}
                message (assoc :message message))]})

(defn outcome? [candidate]
  (m/validate QueryOutcome candidate))

(defn available? [outcome]
  (contains? #{:ok :degraded} (:status outcome)))

(defn value-or [outcome fallback]
  (if (available? outcome) (:value outcome) fallback))

(defn summary [outcome]
  (select-keys outcome [:status :warnings]))

(defn map-value [outcome f]
  (if (available? outcome)
    (update outcome :value f)
    outcome))

(m/=> ok [:=> [:cat :any] QueryOutcome])
(m/=> degraded [:=> [:cat :any [:sequential Warning]] QueryOutcome])
(m/=> failure [:=> [:cat [:enum :timeout :error] :string [:maybe :string]] QueryOutcome])
(m/=> outcome? [:=> [:cat :any] :boolean])
(m/=> available? [:=> [:cat QueryOutcome] :boolean])
(m/=> value-or [:=> [:cat QueryOutcome :any] :any])
(m/=> summary [:=> [:cat QueryOutcome] [:map [:status Status] [:warnings [:vector Warning]]]])
(m/=> map-value [:=> [:cat QueryOutcome fn?] QueryOutcome])
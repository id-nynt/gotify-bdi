// Project-specific beliefs above are generated solely from 03_workflow_model.yaml.
// Generic event-driven plans: post-observations -> pre-observations -> dispatch -> assess.
// Java actions publish later percepts. Result handlers, not action return alone,
// resume the loop. Final-job post-observations remain mandatory for achievement.
// Internal execution_failed is distinct from an explicitly requested failure goal.
workflow_active.

// The elapsed clock is an observation; this generated limit determines stopping.
+postdeploy_elapsed(Entity, Elapsed)
    : postdeploy_limit(Entity, Limit) & Elapsed >= Limit & not workflow_stopped
    <- record_decision(Entity, budget_exhausted, 1);
       !end(stopped, failed).

// ======================
// RULES
// ======================

// DEPENDENCIES
holds([]).
holds([Head | Tail]) :-
    phase_result(Head, success)
    & holds(Tail).

// TERMINAL STATE
terminal(Entity) :- terminal(Entity, _).

// NEXT NORMAL ENTITY
nextentity(Entity) :-
    workflow_active
    & not workflow_stopped
    & required(Entity)
    & entity(Entity)
    & not recovery_entity(Entity)
    & depends(Entity, Requirements)
    & holds(Requirements)
    & safe_to_execute(Entity)
    & not running(_)
    & not terminal(Entity)
    & not phase_result(Entity, success)
    & not phase_result(Entity, failure).

// RETRY: count additional executions, not health observations.
retry_allowed(Entity) :-
    max_retries(Max)
    & attempt_count(Entity, Attempts)
    & Attempts <= Max
    & retry_safe(Entity)
    & not recovery_entity(Entity).
retryable_result(transient_failure).
retryable_result(timeout).
retry_pending(Entity) :-
    failure_reason(Entity, Result)
    & retryable_result(Result)
    & retry_allowed(Entity).

// MAINTENANCE: observations stay qualified by entity attempt.
duration_ok(Entity, Time, Max) :-
    max_duration(Entity, Max)
    & attempt_count(Entity, Attempt)
    & duration(Entity, Attempt, Time)
    & Time <= Max.
duration_violation(Entity, Time, Max) :-
    max_duration(Entity, Max)
    & attempt_count(Entity, Attempt)
    & duration(Entity, Attempt, Time)
    & Time > Max.
maintenance_violation(Entity) :- duration_violation(Entity, _, _).

// AVOIDANCE: check prerequisites before dispatch, not only after deployment.
avoidance_violation(Entity) :-
    avoid_missing(Entity, Required)
    & not phase_result(Required, success).
safe_to_execute(Entity) :- not avoidance_violation(Entity).
verify_after(Entity) :- require_healthy(Entity).
verify_after(Entity) :- observe_after(Entity).
ready_to_execute(Entity) :- not observe_before(Entity, _).
ready_to_execute(Entity) :-
    observe_before(Entity, Source)
    & telemetry(Source, allow).

// GOAL ASSESSMENT: final job success alone is insufficient.
achievement_satisfied(Entity, Desired) :-
    achievement(Entity, Desired)
    & phase_result(Entity, Desired).
achievement_unsatisfied(Entity, Desired) :-
    achievement(Entity, Desired)
    & not phase_result(Entity, Desired).
all_achievements_satisfied :- not achievement_unsatisfied(_, _).
required_unsatisfied(Entity) :- required(Entity) & achievement(Entity, Desired) & not phase_result(Entity, Desired).
required_unsatisfied(Entity) :- required(Entity) & not achievement(Entity, _) & not phase_result(Entity, success).
health_unsatisfied(Entity) :- required(Entity) & phase_result(Entity, success) & verify_after(Entity) & not telemetry(Entity, allow).
health_unsatisfied(Entity) :- require_healthy(Entity) & not telemetry(Entity, allow).
all_goals_satisfied :-
    all_achievements_satisfied
    & not required_unsatisfied(_)
    & not health_unsatisfied(_).

// ======================
// INITIAL GOAL
// ======================

!master_goal.

// MASTER GOAL
+!master_goal
    : achievement(Entity, Desired)
    <- .print("Master goal started.");
       !need_achieve(Entity, Desired).

// ======================
// ACHIEVEMENT GOALS
// ======================

// WORKFLOW ALREADY STOPPED
+!need_achieve(Entity, Desired)
    : workflow_stopped
    <- .print("Workflow stopped before achievement: ", Entity, " = ", Desired).

// ALREADY ACHIEVED AND VERIFIED
+!need_achieve(Entity, Desired)
    : master_goal_achieved
    <- .print("Achievement satisfied: ", Entity, " = ", Desired).

// START WORKFLOW
+!need_achieve(Entity, Desired)
    : not workflow_started & not workflow_stopped
    <- +workflow_started;
       .print("Pursuing achievement: ", Entity, " = ", Desired);
       !run_pipeline.

// WORKFLOW ALREADY RUNNING
+!need_achieve(Entity, Desired)
    : workflow_started & not workflow_stopped & not master_goal_achieved
    <- .print("Achievement pending: ", Entity, " = ", Desired).

// ======================
// EXECUTION LOOP: enter stage 1, then advance only when its checks permit it.
// ======================
+!run_pipeline
    : not workflow_active
    <- true.
+!run_pipeline
    : workflow_active & (running(_) | observing(_))
    <- true.
+!run_pipeline
    : workflow_active & not running(_) & not observing(_)
    <- .print("BDI_STAGE=1 post_observations");
       !check_post_observations.

// ======================
// 1. CHECK POST-OBSERVATIONS OF PREVIOUS JOBS
// Health callbacks return to run_pipeline, which rechecks the outstanding work.
// ======================
+!check_post_observations
    : workflow_active & required(Entity) & phase_result(Entity, success)
      & verify_after(Entity) & telemetry(Entity, block)
    <- !failed(Entity, telemetry_block, stopped).
+!check_post_observations
    : workflow_active & required(Entity) & phase_result(Entity, success)
      & verify_after(Entity) & telemetry(Entity, unknown)
    <- !failed(Entity, telemetry_unknown, unknown).
+!check_post_observations
    : workflow_active & required(Entity) & phase_result(Entity, success)
      & verify_after(Entity) & not telemetry(Entity, _) & not observing(Entity)
    <- +observing(Entity);
       .print("BDI_DECISION=observe entity=", Entity);
       observe_telemetry(Entity).
+!check_post_observations
    : workflow_active
      & not (required(Entity) & phase_result(Entity, success)
             & verify_after(Entity) & not telemetry(Entity, allow))
    <- .print("BDI_STAGE=2 pre_observations");
       !check_pre_observations.

// ======================
// 2. CHECK PRE-OBSERVATIONS OF THE NEXT JOB
// An already accepted source observation satisfies the current model's precondition.
// If delivery is already complete, proceed directly to stage 4.
// ======================
+!check_pre_observations
    : workflow_active & all_goals_satisfied
    <- !assess_goals.
+!check_pre_observations
    : nextentity(Entity) & observe_before(Entity, Source) & telemetry(Source, block)
    <- !failed(Source, telemetry_block, stopped).
+!check_pre_observations
    : nextentity(Entity) & observe_before(Entity, Source) & telemetry(Source, unknown)
    <- !failed(Source, telemetry_unknown, unknown).
+!check_pre_observations
    : nextentity(Entity) & observe_before(Entity, Source) & not telemetry(Source, _)
    <- +observing(Source);
       .print("BDI_DECISION=observe entity=", Source);
       observe_telemetry(Source).
+!check_pre_observations
    : not all_goals_satisfied & nextentity(Entity) & ready_to_execute(Entity)
    <- !run_entity(Entity).
+!check_pre_observations
    : workflow_active & not all_goals_satisfied
      & not running(_) & not observing(_) & not nextentity(_)
    <- .print("Pipeline cannot make further progress.");
       !end(stopped, not_needed).

// ======================
// 3. EXECUTE THE JOB AND TRACK ITS ATTEMPT
// ======================

// START THE SELECTED COMPLETE YAML JOB.
// Per-entity Attempt is also passed to Java for observation correlation.
+!run_entity(Entity)
    : entity(Entity) & not running(_) & not terminal(Entity)
      & attempt_count(Entity, PreviousAttempts)
    <- .print("BDI_STAGE=3 execute entity=", Entity);
       Attempt = PreviousAttempts + 1;
       -attempt_count(Entity, PreviousAttempts);
       +attempt_count(Entity, Attempt);
       -phase_result(Entity, _);
       -failure_reason(Entity, _);
       -telemetry(Entity, _);
       -healthy_count(Entity, _);
       +healthy_count(Entity, 0);
       +running(Entity);
       +run_attempt(Entity, Attempt);
       .print("BDI_DECISION=run entity=", Entity, " attempt=", Attempt);
       run_job(Entity, Attempt).

// ======================
// 4. ASSESS GOALS AFTER A JOB RESULT
// Declaring success requires all goals, health, maintenance and safety checks.
// If observations or jobs remain, stage 1 begins the next cycle.
// ======================
+!assess_goals
    : not workflow_active
    <- true.
+!assess_goals
    : workflow_active & all_goals_satisfied
    <- .print("BDI_STAGE=4 assess_goals");
       !check_master_goal.
+!assess_goals
    : workflow_active & not all_goals_satisfied
    <- .print("BDI_STAGE=4 assess_goals");
       !run_pipeline.

// ======================
// OBSERVATIONS -> PHASE RESULTS
// ======================

// SUCCESS WITHOUT A DURATION REQUIREMENT
+status(Entity, Attempt, success)
    : running(Entity) & run_attempt(Entity, Attempt)
      & not max_duration(Entity, _) & not phase_result(Entity, success)
    <- +phase_result(Entity, success).

// SUCCESS WITH DURATION ALREADY AVAILABLE
+status(Entity, Attempt, success)
    : running(Entity) & run_attempt(Entity, Attempt)
      & max_duration(Entity, _) & duration(Entity, Attempt, _)
      & not phase_result(Entity, success)
    <- +phase_result(Entity, success).

// DURATION ARRIVES AFTER SUCCESS: only the matching attempt is accepted.
+duration(Entity, Attempt, Time)
    : running(Entity) & run_attempt(Entity, Attempt)
      & status(Entity, Attempt, success) & max_duration(Entity, _)
      & not phase_result(Entity, success)
    <- +phase_result(Entity, success).

// If the goal asks for this job to fail and it fails, record that outcome.
// Do not retry merely to make it succeed. This does not describe ordinary fault injection.
+status(Entity, Attempt, failure)
    : running(Entity) & run_attempt(Entity, Attempt) & achievement(Entity, failure)
      & (not max_duration(Entity, _) | duration(Entity, Attempt, _))
    <- +phase_result(Entity, failure).
+duration(Entity, Attempt, Time)
    : running(Entity) & run_attempt(Entity, Attempt) & achievement(Entity, failure)
      & status(Entity, Attempt, failure) & max_duration(Entity, _) & not phase_result(Entity, failure)
    <- +phase_result(Entity, failure).

// CONFIRMED FAILURE: retain its type for the retry/recovery decision.
+status(Entity, Attempt, Result)
    : running(Entity) & run_attempt(Entity, Attempt)
      & Result \== success & Result \== unknown & not phase_result(Entity, execution_failed)
      & not (Result == failure & achievement(Entity, failure))
    <- +failure_reason(Entity, Result);
       .print("Failure observation accepted: ", Entity, " attempt ", Attempt, " = ", Result);
       +phase_result(Entity, execution_failed).

// UNCERTAIN EXECUTION IS NOT A CONFIRMED FAILURE.
+status(Entity, Attempt, unknown)
    : running(Entity) & run_attempt(Entity, Attempt)
    <- .print("BDI_DECISION=reconcile entity=", Entity);
       reconcile_job(Entity, Attempt, 1).
+reconciled(Entity, Attempt, Round, unknown)
    : running(Entity) & run_attempt(Entity, Attempt)
      & reconciliation_limit(Max) & Round < Max & reconciliation_interval(Delay)
    <- .wait(Delay);
       Next = Round + 1;
       reconcile_job(Entity, Attempt, Next).
+reconciled(Entity, Attempt, Round, unknown)
    : running(Entity) & run_attempt(Entity, Attempt)
      & reconciliation_limit(Max) & Round >= Max
    <- -running(Entity);
       -run_attempt(Entity, Attempt);
       .print("BDI_STOP=execution_uncertain entity=", Entity);
       !end(unknown, unresolved).
+reconciled(Entity, Attempt, Round, Result)
    : running(Entity) & run_attempt(Entity, Attempt) & Result \== unknown
    <- +status(Entity, Attempt, Result).

// ======================
// PHASE RESULT EVENTS
// ======================

// EXPLICIT NEGATIVE GOAL: the requested failure occurred; assess other goals.
// Success-dependent jobs remain blocked.
+phase_result(Entity, failure)
    : running(Entity) & achievement(Entity, failure)
    <- -running(Entity);
       -run_attempt(Entity, _);
       .print("Expected failure observed: ", Entity);
       !maintain(Entity);
       !assess_goals.

// NORMAL ENTITY SUCCESS
+phase_result(Entity, success)
    : running(Entity) & not recovery_entity(Entity)
    <- -running(Entity);
       -run_attempt(Entity, _);
       .print("BDI_BELIEF=success entity=", Entity);
       !maintain(Entity);
       !check_avoidance;
       !assess_goals.

// NORMAL ENTITY FAILURE -> RETRY
+phase_result(Entity, execution_failed)
    : running(Entity) & not recovery_entity(Entity)
      & retry_pending(Entity) & attempt_count(Entity, Attempt) & retry_interval(Delay)
    <- -running(Entity);
       -run_attempt(Entity, _);
       -phase_result(Entity, execution_failed);
       .print("BDI_DECISION=retry entity=", Entity, " within configured retry budget");
       record_decision(Entity, retry, Attempt);
       .wait(Delay);
       !run_pipeline.

// REJECTED DISPATCH -> STOP (not an executed deployment failure)
+phase_result(Entity, execution_failed)
    : running(Entity) & not recovery_entity(Entity) & failure_reason(Entity, dispatch_rejected)
    <- -running(Entity);
       -run_attempt(Entity, _);
       !failed(Entity, dispatch_rejected, stopped).

// NORMAL ENTITY FAILURE -> CONFIGURED RECOVERY OR STOP
+phase_result(Entity, execution_failed)
    : running(Entity) & not recovery_entity(Entity) & not retry_pending(Entity)
      & failure_reason(Entity, Result) & Result \== dispatch_rejected
    <- -running(Entity);
       -run_attempt(Entity, _);
       !failed(Entity, failure, stopped).

// ======================
// RECOVERY
// ======================

// REMEMBER THE FAILURE CONTEXT; !recover CHOOSES RECOVERY OR STOP.
+!failed(Entity, Reason, Outcome)
    : workflow_active
    <- -phase_result(Entity, _);
       +terminal(Entity, Reason);
       +failure_context(Entity, Reason, Outcome);
       !recover(Entity).
+!failed(Entity, Reason, Outcome)
    : recovery_active(_, Entity)
    <- .print("BDI_RECOVERY=failed entity=", Entity, " reason=", Reason);
       !end(stopped, failed).

// Select rollback as a pending intention, never as an already-dispatched action.
// Only exhausted request-degradation observations can be reconsidered; failed jobs
// and failed/uncertain repair operations retain their existing safe fallback.
+!recover(Entity)
    : workflow_active & failure_context(Entity, telemetry_block, _)
      & recovery(Entity, Recovery) & recover_on(Entity, telemetry_block, Recovery)
      & known_good_available & safe_to_execute(Recovery) & not recovery_attempted(Entity)
      & reconsideration_window(Entity, _) & not reconsidered(Entity) & not repair_checked(Entity)
    <- -workflow_active;
       +reconsidered(Entity);
       +recovery_pending(Entity, Recovery);
       .abolish(telemetry_sample(Entity, _, _, _, _));
       -healthy_count(Entity, _); +healthy_count(Entity, 0);
       +observing(Entity);
       .print("BDI_DECISION=rollback_selected entity=", Entity);
       begin_reconsideration(Entity);
       observe_telemetry(Entity).

+!commit_pending_recovery(Entity)
    : recovery_pending(Entity, Recovery)
    <- -recovery_pending(Entity, Recovery);
       -observing(Entity);
       record_decision(Entity, rollback_committed, 1);
       +workflow_active;
       !recover(Entity).

// START RECOVERY: mapping, trigger, known-good source and single-attempt guard.
+!recover(Entity)
    : workflow_active & failure_context(Entity, Reason, _)
      & recovery(Entity, Recovery) & recover_on(Entity, Reason, Recovery)
      & known_good_available & safe_to_execute(Recovery) & not recovery_attempted(Entity)
    <- -workflow_active;
       +recovery_attempted(Entity);
       +recovery_active(Entity, Recovery);
       .print("BDI_DECISION=rollback source=", Entity, " entity=", Recovery, " reason=", Reason);
       record_recovery(Entity, Recovery, Reason);
       !run_entity(Recovery).

// NO APPLICABLE VERIFIED RECOVERY -> STOP
+!recover(Entity)
    : workflow_active & failure_context(Entity, Reason, Outcome)
    <- .print("BDI_STOP=", Reason, " entity=", Entity);
       !end(Outcome, not_attempted).

// RECOVERY EXECUTION SUCCESS -> VERIFY RESTORED HEALTH
+phase_result(Recovery, success)
    : running(Recovery) & recovery_entity(Recovery) & recovery_active(_, Recovery)
    <- -running(Recovery);
       -run_attempt(Recovery, _);
       +observing(Recovery);
       .print("BDI_DECISION=verify_recovery entity=", Recovery);
       observe_telemetry(Recovery).

// RECOVERY FAILURE -> STOP; never retry the recovery action.
+phase_result(Recovery, execution_failed)
    : running(Recovery) & recovery_entity(Recovery) & recovery_active(_, Recovery)
    <- -running(Recovery);
       -run_attempt(Recovery, _);
       +terminal(Recovery, failed);
       !failed(Recovery, failure, stopped).

// ======================
// MAINTENANCE
// ======================

+!maintain(Entity)
    : not max_duration(Entity, _)
    <- true.
+!maintain(Entity)
    : duration_ok(Entity, Time, Max)
    <- .print("Maintenance satisfied: ", Entity, " duration ", Time, " <= ", Max).
+!maintain(Entity)
    : duration_violation(Entity, Time, Max)
    <- .print("Maintenance violated: ", Entity, " duration ", Time, " > ", Max);
       !failed(Entity, maintenance_violation, stopped).

// ======================
// AVOIDANCE
// ======================

// Do not reassess candidate prerequisites during recovery.
+!check_avoidance
    : not workflow_active
    <- true.
+!check_avoidance
    : workflow_active & not (required(Entity) & phase_result(Entity, success) & avoidance_violation(Entity))
    <- true.
+!check_avoidance
    : workflow_active & required(Entity) & phase_result(Entity, success) & avoidance_violation(Entity)
    <- .print("Avoidance violation detected for ", Entity);
       !failed(Entity, avoidance_violation, stopped).

// ======================
// HEALTH OBSERVATION AND REOBSERVATION
// ======================

// Measurements are data. These AgentSpeak rules apply engineer-defined constraints.
+telemetry_measurement(Entity, Attempt, Round, unavailable, _, _, _, _, Time)
    : observing(Entity) & attempt_count(Entity, Attempt)
    <- +telemetry_sample(Entity, Attempt, Round, unknown, Time).
+telemetry_measurement(Entity, Attempt, Round, fresh, not_ready, _, _, _, Time)
    : observing(Entity) & attempt_count(Entity, Attempt)
    <- +telemetry_sample(Entity, Attempt, Round, block, Time).
+telemetry_measurement(Entity, Attempt, Round, fresh, ready, Error, Latency, Availability, Time)
    : observing(Entity) & attempt_count(Entity, Attempt) & error_rate_limit(MaxError) & latency_limit(MaxLatency)
      & (Error > MaxError | Latency > MaxLatency | Availability < 1)
    <- +telemetry_sample(Entity, Attempt, Round, block, Time).
+telemetry_measurement(Entity, Attempt, Round, fresh, ready, Error, Latency, Availability, Time)
    : observing(Entity) & attempt_count(Entity, Attempt) & error_rate_limit(MaxError) & latency_limit(MaxLatency)
      & Error <= MaxError & Latency <= MaxLatency & Availability >= 1
    <- +telemetry_sample(Entity, Attempt, Round, allow, Time).

// Recheck results still arrive through percepts. No action assumes observation completion.
+telemetry_sample(Entity, Attempt, Round, Decision, Time)
    : recovery_pending(Entity, _) & observing(Entity) & attempt_count(Entity, Attempt)
      & reconsideration_window(Entity, Limit) & Time >= Limit
    <- !commit_pending_recovery(Entity).
+telemetry_sample(Entity, Attempt, Round, allow, Time)
    : recovery_pending(Entity, _) & observing(Entity) & attempt_count(Entity, Attempt)
      & reconsideration_window(Entity, Limit) & Time < Limit
      & healthy_count(Entity, Count) & healthy_observations(Need) & Count + 1 >= Need
    <- -recovery_pending(Entity, _);
       -observing(Entity); -terminal(Entity, _); -failure_context(Entity, _, _);
       -telemetry(Entity, _); +telemetry(Entity, allow);
       +phase_result(Entity, success);
       accept_telemetry(Entity, allow);
       record_decision(Entity, rollback_cancelled, 1);
       .print("BDI_DECISION=rollback_cancelled resume_candidate entity=", Entity);
       +workflow_active;
       !run_pipeline.
+telemetry_sample(Entity, Attempt, Round, allow, Time)
    : recovery_pending(Entity, _) & observing(Entity) & attempt_count(Entity, Attempt)
      & reconsideration_window(Entity, Limit) & Time < Limit
      & healthy_count(Entity, Count) & healthy_observations(Need) & Count + 1 < Need
    <- -healthy_count(Entity, Count); Next = Count + 1; +healthy_count(Entity, Next);
       !reobserve(Entity, Round).
+telemetry_sample(Entity, Attempt, Round, Decision, Time)
    : recovery_pending(Entity, _) & observing(Entity) & attempt_count(Entity, Attempt)
      & reconsideration_window(Entity, Limit) & Time < Limit & Decision \== allow
    <- -healthy_count(Entity, _); +healthy_count(Entity, 0);
       !reobserve(Entity, Round).

observation_open(Round, Time) :- observation_limit(Max) & Round < Max & observation_timeout(Deadline) & Time < Deadline.
+telemetry_sample(Entity, Attempt, Round, allow, Time)
    : observing(Entity) & attempt_count(Entity, Attempt)
    & healthy_count(Entity, Count) & healthy_observations(Need) & Count + 1 >= Need & observation_timeout(Deadline) & Time <= Deadline
    <- !accept_sample(Entity, allow).
+telemetry_sample(Entity, Attempt, Round, allow, Time)
    : observing(Entity) & attempt_count(Entity, Attempt)
    & healthy_count(Entity, Count) & healthy_observations(Need) & Count + 1 < Need & observation_open(Round, Time)
    <- -healthy_count(Entity, Count);
       Next = Count + 1;
       +healthy_count(Entity, Next);
       !reobserve(Entity, Round).
+telemetry_sample(Entity, Attempt, Round, allow, Time)
    : observing(Entity) & attempt_count(Entity, Attempt)
    & healthy_count(Entity, Count) & healthy_observations(Need) & observation_timeout(Deadline)
    & (Count + 1 < Need | Time > Deadline) & not observation_open(Round, Time)
    <- !accept_sample(Entity, unknown).
// Diagnose unavailable services early; ready-but-degraded services reobserve.
// Repairs are subgoals,
// not normal pipeline achievements; the original deployment result stays intact.
+telemetry_sample(Entity, Attempt, Round, Decision, Time)
    : observing(Entity) & attempt_count(Entity, Attempt) & Decision \== allow
      & repair_enabled(Entity) & not repair_checked(Entity) & observation_open(Round, Time)
      & not telemetry_measurement(Entity, Attempt, Round, fresh, ready, _, _, 1, Time)
    <- +repair_checked(Entity);
       +repair_pending_sample(Entity, Attempt, Round);
       .print("BDI_DECISION=diagnose_candidate entity=", Entity);
       diagnose_candidate(Entity, 1).

+candidate_diagnosed(Entity, 1, app_stopped)
    : observing(Entity) & repair_pending_sample(Entity, _, _) & repair_limit(Entity, Max) & Max > 0
    <- .print("BDI_DECISION=restart_candidate entity=", Entity);
       restart_candidate(Entity, 1).
+candidate_diagnosed(Entity, 1, execution_unknown)
    : observing(Entity)
    <- !end(unknown, unresolved).
+candidate_diagnosed(Entity, 1, Result)
    : observing(Entity) & (Result == unknown | Result == failed | Result == exhausted)
    <- -observing(Entity);
       !failed(Entity, telemetry_unknown, stopped).
+candidate_diagnosed(Entity, 1, Result)
    : observing(Entity) & repair_pending_sample(Entity, Attempt, Round)
      & Result \== app_stopped & Result \== execution_unknown & Result \== unknown & Result \== failed & Result \== exhausted
    <- -repair_pending_sample(Entity, Attempt, Round);
       !reobserve(Entity, Round).
+candidate_restarted(Entity, 1, executed)
    : observing(Entity) & repair_pending_sample(Entity, Attempt, Round)
    <- -repair_pending_sample(Entity, Attempt, Round);
       -healthy_count(Entity, _); +healthy_count(Entity, 0);
       +candidate_repair_executed(Entity);
       .print("BDI_DECISION=verify_repair entity=", Entity);
       observe_telemetry(Entity).
+candidate_restarted(Entity, 1, execution_unknown)
    : observing(Entity)
    <- !end(unknown, unresolved).
+candidate_restarted(Entity, 1, Result)
    : observing(Entity) & Result \== executed & Result \== execution_unknown
    <- -observing(Entity);
       .print("BDI_DECISION=repair_fallback entity=", Entity);
       !failed(Entity, telemetry_block, stopped).

+telemetry_sample(Entity, Attempt, Round, Decision, Time)
    : observing(Entity) & attempt_count(Entity, Attempt)
    & Decision \== allow & observation_open(Round, Time)
    & (not repair_enabled(Entity) | repair_checked(Entity)
       | telemetry_measurement(Entity, Attempt, Round, fresh, ready, _, _, 1, Time))
    <- -healthy_count(Entity, _);
       +healthy_count(Entity, 0);
       !reobserve(Entity, Round).
+telemetry_sample(Entity, Attempt, Round, Decision, Time)
    : observing(Entity) & attempt_count(Entity, Attempt)
    & Decision \== allow & not observation_open(Round, Time)
    <- !accept_sample(Entity, Decision).
+!reobserve(Entity, Round)
    : observation_interval(Delay)
    <- .print("BDI_DECISION=wait_reconsider entity=", Entity, " round=", Round);
    record_decision(Entity, reobserve, Round); .wait(Delay); observe_telemetry(Entity).
+!accept_sample(Entity, Decision)
    <- -observing(Entity);
       accept_telemetry(Entity, Decision);
       +telemetry(Entity, Decision);
       !observed(Entity, Decision).
+!observed(Entity, allow)
    : recovery_active(_, Entity)
    <- .print("BDI_RECOVERY=restored entity=", Entity);
       !end(stopped, restored).
+!observed(Entity, block)
    : recovery_active(_, Entity)
    <- .print("BDI_RECOVERY=unhealthy entity=", Entity);
       !end(stopped, failed).
+!observed(Entity, unknown)
    : recovery_active(_, Entity)
    <- .print("BDI_RECOVERY=unverified entity=", Entity);
       !end(unknown, unverified).
+!observed(Entity, allow)
    : workflow_active & candidate_repair_executed(Entity)
    <- record_decision(Entity, repair_verified, 1);
       .print("BDI_DECISION=resume_candidate entity=", Entity);
       !run_pipeline.
+!observed(Entity, Decision)
    : workflow_active
    <- .print("BDI_BELIEF=telemetry entity=", Entity, " decision=", Decision);
       !run_pipeline.


// ======================
// MASTER GOAL ASSESSMENT
// ======================

// ALL GOALS SATISFIED: requires health as well as execution success.
+!check_master_goal
    : workflow_active & all_goals_satisfied
      & not maintenance_violation(_)
      & not (required(Entity) & phase_result(Entity, success) & avoidance_violation(Entity))
    <- +master_goal_achieved;
       +workflow_completed;
       .print("Master goal achieved.");
       !end(achieved, not_needed).

// MASTER GOAL NOT SATISFIED
+!check_master_goal
    : not master_goal_achieved
    <- .print("Master goal not achieved.");
       !end(stopped, not_needed).

// Every terminal outcome is written once by the environment.
// Restoring v1 terminates the campaign; it does not satisfy candidate delivery.
+!end(achieved, Recovery)
    <- -workflow_active;
       +controller_result(achieved);
       +recovery_result(Recovery);
       .print("BDI_WORKFLOW_STATE=completed");
       .print("BDI_CONTROLLER_RESULT=achieved recovery=", Recovery);
       finish(achieved, Recovery).

+!end(Outcome, Recovery)
    : Outcome \== achieved
    <- -workflow_active;
       +workflow_stopped;
       +controller_result(Outcome);
       +recovery_result(Recovery);
       .print("Attempted but failed to achieve goals.");
       .print("BDI_WORKFLOW_STATE=stopped");
       .print("BDI_CONTROLLER_RESULT=", Outcome, " recovery=", Recovery);
       finish(Outcome, Recovery).

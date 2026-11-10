#!/usr/bin/env bash
# Verifies that key business rules are enforced by the schema itself.
# Usage: PGHOST=/tmp PGPORT=55432 PGUSER=postgres ./schema_checks.sh   (databases already migrated)
set -u
pass=0; fail=0
ok()   { if psql -d "$1" -q -v ON_ERROR_STOP=1 -c "$3" >/dev/null 2>&1; then echo "PASS  $2"; pass=$((pass+1)); else echo "FAIL  $2 (expected success)"; fail=$((fail+1)); fi; }
deny() { if psql -d "$1" -q -v ON_ERROR_STOP=1 -c "$3" >/dev/null 2>&1; then echo "FAIL  $2 (expected rejection)"; fail=$((fail+1)); else echo "PASS  $2"; pass=$((pass+1)); fi; }

U1=00000000-0000-0000-0000-000000000001
C1=10000000-0000-0000-0000-000000000001
E1=20000000-0000-0000-0000-000000000001
E2=20000000-0000-0000-0000-000000000002
S1=30000000-0000-0000-0000-000000000001; M1=31000000-0000-0000-0000-000000000001; T1=32000000-0000-0000-0000-000000000001
Q1=40000000-0000-0000-0000-000000000001; V1=41000000-0000-0000-0000-000000000001

echo "== catalog"
ok   catalog "create category"            "INSERT INTO catalog.exam_category (id,name,slug) VALUES ('$C1','Language tests','language-tests')"
ok   catalog "create exam"                "INSERT INTO catalog.exam (id,category_id,title,slug,created_by,updated_by) VALUES ('$E1','$C1','IELTS Academic','ielts-academic','$U1','$U1')"
deny catalog "duplicate slug rejected"    "INSERT INTO catalog.exam (id,category_id,title,slug,created_by,updated_by) VALUES ('$E2','$C1','Other','ielts-academic','$U1','$U1')"
deny catalog "invalid slug format rejected" "INSERT INTO catalog.exam (id,category_id,title,slug,created_by,updated_by) VALUES ('$E2','$C1','Other','IELTS Academic!','$U1','$U1')"
ok   catalog "rename slug"                "UPDATE catalog.exam SET slug='ielts-academic-2026' WHERE id='$E1'"
ok   catalog "old slug kept for redirect" "DO \$\$BEGIN IF NOT EXISTS (SELECT 1 FROM catalog.exam_slug_history WHERE slug='ielts-academic' AND exam_id='$E1') THEN RAISE EXCEPTION 'missing'; END IF; END\$\$"
deny catalog "retired slug can't be reused by another exam" "INSERT INTO catalog.exam (id,category_id,title,slug,created_by,updated_by) VALUES ('$E2','$C1','Other','ielts-academic','$U1','$U1')"
deny catalog "published exam needs published_at" "UPDATE catalog.exam SET status='PUBLISHED' WHERE id='$E1'"
deny catalog "difficulty mix must total 100" "INSERT INTO catalog.exam_mode_config (exam_id,mode,enabled,mix_simple_pct,mix_medium_pct,mix_hard_pct) VALUES ('$E1','PRACTICE',true,50,50,10)"
deny catalog "mock needs a time limit"    "INSERT INTO catalog.exam_mode_config (exam_id,mode,enabled) VALUES ('$E1','MOCK',true)"
ok   catalog "valid mock config"          "INSERT INTO catalog.exam_mode_config (exam_id,mode,enabled,default_count,min_count,max_count,time_limit_seconds) VALUES ('$E1','MOCK',true,40,40,40,3600)"
ok   catalog "content tree"               "INSERT INTO catalog.subject (id,title) VALUES ('$S1','Reading'); INSERT INTO catalog.module (id,subject_id,title) VALUES ('$M1','$S1','Reading skills'); INSERT INTO catalog.topic (id,module_id,title) VALUES ('$T1','$M1','True / False / Not given')"
deny catalog "topic can't be its own prerequisite" "INSERT INTO catalog.topic_prerequisite VALUES ('$T1','$T1')"
ok   catalog "draft question + version"   "INSERT INTO bank_main.question (id,topic_id,created_by,updated_by) VALUES ('$Q1','$T1','$U1','$U1'); INSERT INTO bank_main.question_version (id,question_id,version_no,answer_type,stem_html,stem_text,difficulty,est_seconds,created_by) VALUES ('$V1','$Q1',1,'SINGLE','<p>Q</p>','Q','MEDIUM',75,'$U1')"
deny catalog "solving time outside 10-600 s rejected" "INSERT INTO bank_main.question_version (id,question_id,version_no,answer_type,stem_html,stem_text,difficulty,est_seconds,created_by) VALUES (gen_random_uuid(),'$Q1',2,'SINGLE','<p>Q</p>','Q','MEDIUM',5,'$U1')"
ok   catalog "draft options editable"     "INSERT INTO bank_main.question_option (id,version_id,label,body_html,is_correct) VALUES (gen_random_uuid(),'$V1','A','Dockerfile',false),(gen_random_uuid(),'$V1','B','Kubernetes',true); UPDATE bank_main.question_option SET body_html='Docker file' WHERE version_id='$V1' AND label='A'"
deny catalog "correct option can't carry a misconception" "INSERT INTO bank_main.question_option (id,version_id,label,body_html,is_correct,misconception_id) VALUES (gen_random_uuid(),'$V1','C','x',true,gen_random_uuid())"
ok   catalog "freeze version on publish"  "UPDATE bank_main.question_version SET frozen_at=now() WHERE id='$V1'"
deny catalog "frozen version can't change" "UPDATE bank_main.question_version SET stem_html='<p>edited</p>' WHERE id='$V1'"
deny catalog "frozen version options can't change" "UPDATE bank_main.question_option SET body_html='edited' WHERE version_id='$V1'"
deny catalog "frozen version can't get new options" "INSERT INTO bank_main.question_option (id,version_id,label,body_html,is_correct) VALUES (gen_random_uuid(),'$V1','D','x',false)"
ok   catalog "trial role exists"          "SET ROLE catalog_trial_reader; SELECT count(*) FROM bank_trial.question; RESET ROLE"
deny catalog "trial role can't read main bank" "SET ROLE catalog_trial_reader; SELECT count(*) FROM bank_main.question"
ok   catalog "audit insert"               "INSERT INTO catalog.audit_event (id,actor_id,action,target_type) VALUES (gen_random_uuid(),'$U1','EXAM_CREATED','EXAM')"
deny catalog "audit is append-only"       "DELETE FROM catalog.audit_event"

echo "== assessment"
A1=50000000-0000-0000-0000-000000000001; A2=50000000-0000-0000-0000-000000000002
ok   assessment "start mock attempt"      "INSERT INTO attempt (id,student_id,exam_id,mode,bank,access_reason,config_snapshot,expires_at,idempotency_key) VALUES ('$A1','$U1','$E1','MOCK','MAIN','SUBSCRIPTION','{}',now()+interval '1 hour','k1')"
deny assessment "second active mock for same exam rejected (TM-4)" "INSERT INTO attempt (id,student_id,exam_id,mode,bank,access_reason,config_snapshot,expires_at,idempotency_key) VALUES ('$A2','$U1','$E1','MOCK','MAIN','SUBSCRIPTION','{}',now()+interval '1 hour','k2')"
deny assessment "same idempotency key rejected" "INSERT INTO attempt (id,student_id,exam_id,mode,bank,access_reason,config_snapshot,idempotency_key) VALUES ('$A2','$U1','$E2','PRACTICE','MAIN','SUBSCRIPTION','{}','k1')"
deny assessment "mock without expiry rejected" "INSERT INTO attempt (id,student_id,exam_id,mode,bank,access_reason,config_snapshot,idempotency_key) VALUES ('$A2','$U1','$E2','MOCK','MAIN','SUBSCRIPTION','{}','k3')"
deny assessment "single-answer needs exactly one key" "INSERT INTO attempt_question (attempt_id,position,question_id,question_version_id,topic_id,difficulty,est_seconds,answer_type,option_order,correct_labels,marks) VALUES ('$A1',1,'$Q1','$V1','$T1','MEDIUM',75,'SINGLE','{B,A,D,C}','{A,B}',1)"
ok   assessment "fixed question set"      "INSERT INTO attempt_question (attempt_id,position,question_id,question_version_id,topic_id,difficulty,est_seconds,answer_type,option_order,correct_labels,marks) VALUES ('$A1',1,'$Q1','$V1','$T1','MEDIUM',75,'SINGLE','{B,A,D,C}','{B}',1)"
deny assessment "same question twice in attempt rejected (SG-6)" "INSERT INTO attempt_question (attempt_id,position,question_id,question_version_id,topic_id,difficulty,est_seconds,answer_type,option_order,correct_labels,marks) VALUES ('$A1',2,'$Q1','$V1','$T1','MEDIUM',75,'SINGLE','{A,B,C,D}','{B}',1)"
deny assessment "response to a question not in the attempt rejected" "INSERT INTO response (attempt_id,position,selected_labels) VALUES ('$A1',9,'{A}')"
ok   assessment "save response"           "INSERT INTO response (attempt_id,position,selected_labels) VALUES ('$A1',1,'{A}')"
deny assessment "scored without score rejected" "UPDATE attempt SET status='SCORED' WHERE id='$A1'"
ok   assessment "score attempt"           "UPDATE attempt SET status='SCORED', finish_reason='SUBMITTED', finished_at=now(), score=0, max_score=1 WHERE id='$A1'"
ok   assessment "new mock allowed after scoring" "INSERT INTO attempt (id,student_id,exam_id,mode,bank,access_reason,config_snapshot,expires_at,idempotency_key) VALUES ('$A2','$U1','$E1','MOCK','MAIN','SUBSCRIPTION','{}',now()+interval '1 hour','k2')"

echo "== learning"
ok   learning "record answer"            "INSERT INTO answer_history (student_id,attempt_id,exam_id,mode,topic_id,question_id,question_version_id,difficulty,outcome,answered_at) VALUES ('$U1','$A1','$E1','MOCK','$T1','$Q1','$V1','MEDIUM','INCORRECT',now())"
deny learning "same attempt/question twice rejected (idempotent)" "INSERT INTO answer_history (student_id,attempt_id,exam_id,mode,topic_id,question_id,question_version_id,difficulty,outcome,answered_at) VALUES ('$U1','$A1','$E1','MOCK','$T1','$Q1','$V1','MEDIUM','CORRECT',now())"
deny learning "history is append-only"  "UPDATE answer_history SET outcome='CORRECT'"
ok   learning "pending recommendation"   "INSERT INTO recommendation (id,student_id,exam_id,type,topic_ids,question_count,reason,evidence) VALUES (gen_random_uuid(),'$U1','$E1','TARGETED','{$T1}',10,'4 of last 10 correct','{}')"
deny learning "only one pending recommendation per exam" "INSERT INTO recommendation (id,student_id,exam_id,type,topic_ids,question_count,reason,evidence) VALUES (gen_random_uuid(),'$U1','$E1','BALANCED','{$T1}',10,'x','{}')"
deny learning "level outside 1-3 rejected" "INSERT INTO topic_mastery (student_id,topic_id,exam_id,level) VALUES ('$U1','$T1','$E1',4)"

echo "== billing"
P1=60000000-0000-0000-0000-000000000001; X1=61000000-0000-0000-0000-000000000001
ok   billing "count a free attempt"       "INSERT INTO free_attempt_use (attempt_id,student_id,exam_id,mode) VALUES ('$X1','$U1','$P1','MOCK')"
deny billing "an attempt counts once"     "INSERT INTO free_attempt_use (attempt_id,student_id,exam_id,mode) VALUES ('$X1','$U1','$P1','PRACTICE')"
deny billing "unknown mode rejected"      "INSERT INTO free_attempt_use (attempt_id,student_id,exam_id,mode) VALUES ('$P1','$U1','$P1','SPEED')"
deny billing "plan duration must be 1/3/6/12" "INSERT INTO subscription_plan (id,code,name,duration_months,price_lkr_minor) VALUES ('$P1','p2','Two months',2,1000)"
deny billing "plan needs a price"         "INSERT INTO subscription_plan (id,code,name,duration_months) VALUES (gen_random_uuid(),'np','No price',1)"
deny billing "plan scope must be known"   "INSERT INTO subscription_plan (id,code,name,duration_months,price_usd_minor,scope_type) VALUES (gen_random_uuid(),'sc','Scope',1,1500,'SUBJECT')"
deny billing "negative attempt limit rejected" "INSERT INTO subscription_plan (id,code,name,duration_months,price_usd_minor,limit_mock) VALUES (gen_random_uuid(),'lm','Limit',1,1500,-1)"
ok   billing "create plan"                "INSERT INTO subscription_plan (id,code,name,duration_months,price_lkr_minor,price_usd_minor,active,scope_type,limit_mock) VALUES ('$P1','m6','6 months',6,450000,2000,true,'EXAM',8)"
ok   billing "plan covers an exam"        "INSERT INTO plan_exam (plan_id,exam_id) VALUES ('$P1','$U1')"
deny billing "payment needs the plan copied" "INSERT INTO payment_transaction (id,student_id,plan_id,provider,idempotency_key,amount_minor,currency) VALUES (gen_random_uuid(),'$U1','$P1','PAYHERE','ik0',450000,'LKR')"
deny billing "unknown payment provider rejected" "INSERT INTO payment_transaction (id,student_id,plan_id,plan_name,duration_months,provider,idempotency_key,amount_minor,currency) VALUES (gen_random_uuid(),'$U1','$P1','6 months',6,'SANDBOX','ik9',450000,'LKR')"
ok   billing "pending payment"            "INSERT INTO payment_transaction (id,student_id,plan_id,plan_name,duration_months,provider,provider_ref,idempotency_key,amount_minor,currency) VALUES ('$X1','$U1','$P1','6 months',6,'PAYHERE','cs_1','ik1',450000,'LKR')"
deny billing "duplicate checkout key rejected" "INSERT INTO payment_transaction (id,student_id,plan_id,plan_name,duration_months,provider,idempotency_key,amount_minor,currency) VALUES (gen_random_uuid(),'$U1','$P1','6 months',6,'PAYHERE','ik1',450000,'LKR')"
ok   billing "record webhook once"        "INSERT INTO webhook_event (provider,provider_event_id,event_type,payload_sha256) VALUES ('SANDBOX','evt_1','payment.succeeded','\\x00')"
deny billing "duplicate webhook rejected" "INSERT INTO webhook_event (provider,provider_event_id,event_type,payload_sha256) VALUES ('SANDBOX','evt_1','payment.succeeded','\\x00')"
ok   billing "activate subscription"      "INSERT INTO subscription (id,student_id,plan_id,transaction_id,status,starts_at,ends_at) VALUES (gen_random_uuid(),'$U1','$P1','$X1','ACTIVE',now(),now()+interval '6 months')"
deny billing "one payment can't create two periods" "INSERT INTO subscription (id,student_id,plan_id,transaction_id,status,starts_at,ends_at) VALUES (gen_random_uuid(),'$U1','$P1','$X1','ACTIVE',now(),now()+interval '6 months')"
deny billing "manual entitlement needs granted_by" "INSERT INTO entitlement (id,student_id,source,starts_at,ends_at) VALUES (gen_random_uuid(),'$U1','MANUAL',now(),now()+interval '1 day')"
deny billing "revocation needs a reason"  "INSERT INTO entitlement (id,student_id,source,granted_by,starts_at,ends_at,revoked_at) VALUES (gen_random_uuid(),'$U1','MANUAL','$U1',now(),now()+interval '1 day',now())"
deny billing "audit is append-only"       "INSERT INTO audit_event (id,action,target_type) VALUES (gen_random_uuid(),'X','Y'); UPDATE audit_event SET action='Z'"

echo "== identity"
ok   identity "create user"               "INSERT INTO app_user (id,google_subject,email,display_name) VALUES ('$U1','g-1','Amaya@Example.com','Amaya')"
deny identity "email unique, case-insensitive" "INSERT INTO app_user (id,google_subject,email,display_name) VALUES (gen_random_uuid(),'g-2','amaya@example.com','Other')"
deny identity "unknown role rejected"     "INSERT INTO user_role (user_id,role) VALUES ('$U1','SUPERUSER')"
ok   identity "grant student role"        "INSERT INTO user_role (user_id,role) VALUES ('$U1','STUDENT')"
deny identity "admin bootstrap runs once" "INSERT INTO admin_bootstrap (email) VALUES ('a@x.com'); INSERT INTO admin_bootstrap (email) VALUES ('b@x.com')"

echo; echo "Result: $pass passed, $fail failed"; [ "$fail" -eq 0 ]

# MedVoice — 108 Voice Intents

Reference for every intent the on-device model (`medical_intent.onnx`) can
recognize. Say any phrasing close to the example — the classifier is trained on
40 variants per intent. In the MedVoice prototype, vitals/symptoms/notes/chart
intents update the patient chart; critical intents (💊 ⚠️) require an on-screen
confirmation; the rest are recognized and logged (integration points for a real
hospital system).

Русская версия: [INTENTS_RU.md](INTENTS_RU.md)

## Batch dictation

One utterance may carry several commands separated by natural pauses/commas:
*"BP 120 over 80, pulse 72, temp is 36.8, sats 97"* records four vitals at
once. Feedback is a single spoken summary; clinical alerts and NEWS2
escalations are always spoken individually. If a critical command appears
mid-batch, everything after it is skipped until you confirm or cancel.

## Note dictation: SOAP sections

In note-dictation mode (📝) the clinical note is structured as SOAP. Say a
standalone phrase to switch sections — **"go to plan"**, **"start
assessment"**, **"switch to objective"**, **"subjective"** — and everything
you dictate afterwards lands in that section. Content that merely mentions
these words ("the patient plans to walk") is appended as text, not treated
as navigation.

## Voice chart review (ask & listen)

Chart review is *reading*, not entry — so this is a different mode: you ask,
the chart answers by voice, and the spoken answer is mirrored in an on-screen
read-back card (a misheard vital is a hazard). Nothing is written. Recognized
by exact phrase, no model needed. It never invents — absent data is reported
as "no X on record".

| Say | Answers with |
|---|---|
| "any critical values" · "any red flags" | Flagged vitals + NEWS2 status — bedside triage |
| "catch me up" · "patient summary" · "SBAR" | Spoken SBAR handover: who, NEWS2 + trend, notable vitals, plan, last event |
| "latest vitals" · "read the vitals" | Every vital on the chart, in order |
| "what's the blood pressure" · "sats trend" · "latest pulse" | One vital's latest value with its trend |
| "what's the NEWS score" | NEWS2 score and risk band |
| "read the plan" · "read the assessment" · "read the note" | A SOAP section, or the whole note |
| "what's been done" · "recap" | Recent chart activity from the audit log |

Answers are drawn only from data MedVoice actually stores (vitals + history,
NEWS2, SOAP notes, audit log). Medications, labs and diagnoses are recognized
as commands but not yet stored, so they are not read back.

## NEWS2 early-warning score

The chart continuously computes NEWS2 from the five voice-captured vitals
(respiratory rate, SpO₂, temperature, systolic BP, heart rate; consciousness
and supplemental O₂ are not captured, so the score is a lower bound). At
score ≥ 5 the app speaks *"consider urgent review"*, at ≥ 7 *"emergency
response required"*. Each recorded vital is also announced with its trend
against the previous value ("down from 94").

---

## EMR — Electronic Medical Record (14)

| Intent | Example | Description |
|---|---|---|
| `emr_open_patient_chart` | "open patient **file** for Jane Roe" (accent-robust), "open patient chart for…", "switch to patient" | Opens a patient's chart; with a name, switches directly to that patient, otherwise shows the patient list |
| `emr_add_patient` | "add a new patient" | Opens the new-patient form (name, MRN, ward) |
| `emr_undo_last` | "**scratch that**" (accent-robust shortcut), "undo the last entry" | Deletes the last recorded vital (same as the spoken "undo" shortcut, but catches longer phrasings) |
| `emr_create_clinical_note` | "create a new clinical note" | Starts a new clinical note |
| `emr_append_to_note` | "add to the note" | Appends dictated text to the current note |
| `emr_save_clinical_note` | "save the note" | Saves the current clinical note |
| `emr_dictate_surgery_report` | "start dictating surgery report" | Begins dictation of an operative report |
| `emr_add_allergy_warning` | "add allergy warning" | Adds an allergy alert to the chart |
| `emr_update_medical_history` | "update the medical history" | Edits the patient's medical history |
| `emr_view_past_diagnoses` | "show past diagnoses" | Shows previous diagnoses |
| `emr_search_patient_records` | "find patient records" | Searches patient records |
| `emr_confirm_patient_identity` | "verify patient identity" | Confirms the patient's identity (safety check) |
| `emr_log_patient_consent` | "record patient consent" | Logs the patient's informed consent |
| `emr_sign_document` ⚠️ | "sign the document" | Digitally signs a document (confirmation required) |

## Vitals (7)

| Intent | Example | Description |
|---|---|---|
| `vitals_log_blood_pressure` | "record blood pressure 120 over 80" | Records blood pressure into the chart |
| `vitals_log_heart_rate` | "log heart rate 72" | Records heart rate |
| `vitals_log_temperature` | "log temperature 38.5" | Records body temperature (°C or °F, converted to °C) |
| `vitals_log_oxygen_saturation` | "oxygen saturation 97" | Records SpO₂ |
| `vitals_log_respiratory_rate` | "log respiratory rate 18" | Records respiratory rate |
| `vitals_log_weight` | "record weight 82 kilograms" | Records body weight |
| `vitals_display_history` | "show vitals history" | Displays the vitals history |

## Symptoms (4)

| Intent | Example | Description |
|---|---|---|
| `symptom_log_pain_level` | "pain level 7 out of 10" | Records pain level on a 0–10 scale |
| `symptom_log_fever` | "log fever" | Logs fever as a symptom |
| `symptom_log_nausea` | "record nausea" | Logs nausea |
| `symptom_log_dizziness` | "log dizziness" | Logs dizziness |

## Medications (10)

| Intent | Example | Description |
|---|---|---|
| `med_prescribe_medication` 💊 | "prescribe a medication" | Creates a prescription (confirmation required) |
| `med_stop_medication` 💊 | "stop the medication" | Discontinues a medication (confirmation required) |
| `med_adjust_dosage` 💊 | "adjust the dosage" | Changes a medication dosage (confirmation required) |
| `med_order_iv_fluids` 💊 | "order IV fluids" | Orders intravenous fluids (confirmation required) |
| `med_refill_prescription` 💊 | "refill the prescription" | Renews a prescription (confirmation required) |
| `med_view_current` | "show current medications" | Shows the current medication list |
| `med_check_interactions` | "check drug interactions" | Checks for drug–drug interactions |
| `med_check_allergies` | "check patient allergies" | Checks the patient's allergy list |
| `med_log_administered` | "log medication administered" | Records that a dose was administered |
| `med_set_reminder` | "set a medication reminder" | Sets a medication reminder |

## Lab & Imaging (11)

| Intent | Example | Description |
|---|---|---|
| `lab_order_blood_test` | "order a blood test" | Orders blood work |
| `lab_order_mri` | "order an MRI" | Orders an MRI scan |
| `lab_order_ct_scan` | "order a CT scan" | Orders a CT scan |
| `lab_order_xray` | "order an X-ray" | Orders an X-ray |
| `lab_order_ultrasound` | "order an ultrasound" | Orders an ultrasound / sonogram |
| `lab_order_ecg` | "order an ECG" | Orders an ECG/EKG |
| `lab_view_results` | "show lab results" | Shows laboratory results |
| `lab_check_blood_panel` | "check the blood panel" | Shows the blood panel |
| `lab_alert_abnormal` | "alert on abnormal labs" | Sets an alert for abnormal lab values |
| `lab_share_with_patient` | "share results with patient" | Sends results to the patient |
| `lab_send_to_specialist` | "send results to specialist" | Forwards results to a specialist |

## Clinical Calculators (3)

| Intent | Example | Description |
|---|---|---|
| `calc_bmi` | "calculate the BMI" | Calculates body mass index |
| `calc_glasgow_scale` | "calculate Glasgow coma scale" | Opens the Glasgow Coma Scale calculator |
| `calc_apgar_score` | "calculate the Apgar score" | Opens the Apgar score calculator (newborns) |

## Emergency (4)

| Intent | Example | Description |
|---|---|---|
| `emrgy_call_code_blue` ⚠️ | "call code blue" | Triggers a cardiac-arrest alarm (confirmation required) |
| `emrgy_request_defibrillator` | "request a defibrillator" | Requests a defibrillator to the location |
| `emrgy_request_blood_units` | "request units of blood" | Requests blood units for transfusion |
| `emrgy_start_triage` | "start triage" | Starts the triage protocol (mass casualty) |

## Clinical Guides (5)

| Intent | Example | Description |
|---|---|---|
| `guide_view_cpr_steps` | "show CPR steps" | Shows CPR instructions |
| `guide_sepsis_protocol` | "show sepsis protocol" | Opens the sepsis management protocol |
| `guide_stroke_protocol` | "show stroke protocol" | Opens the stroke protocol |
| `guide_mi_protocol` | "show MI protocol" | Opens the myocardial infarction protocol |
| `guide_check_contraindications` | "check contraindications" | Shows contraindications |

## Operating Room (10)

| Intent | Example | Description |
|---|---|---|
| `or_start_surgery_timer` | "start the surgery timer" | Starts the operation timer |
| `or_stop_surgery_timer` | "stop the surgery timer" | Stops the operation timer |
| `or_log_anesthesia` | "log anesthesia" | Records anesthesia administration |
| `or_log_implant_serial` | "log the implant serial number" | Records an implant serial number |
| `or_display_vitals_screen` | "show vitals screen" | Shows the intraoperative vitals screen |
| `or_capture_photo` | "capture a photo" | Takes a photo (surgical field documentation) |
| `or_record_video_start` | "start recording" | Starts video recording |
| `or_record_video_stop` | "stop recording" | Stops video recording |
| `or_zoom_camera_in` | "zoom the camera in" | Zooms the camera in |
| `or_zoom_camera_out` | "zoom the camera out" | Zooms the camera out |

## Staff & Patient Flow (10)

| Intent | Example | Description |
|---|---|---|
| `staff_admit_patient` | "admit the patient" | Starts patient admission |
| `staff_discharge_patient` ⚠️ | "discharge the patient" | Discharges the patient (confirmation required) |
| `staff_transfer_patient_icu` ⚠️ | "transfer to ICU" | Transfers the patient to intensive care (confirmation required) |
| `staff_transfer_patient_ward` ⚠️ | "transfer to ward" | Transfers the patient to a ward (confirmation required) |
| `staff_schedule_appointment` | "schedule an appointment" | Books an appointment |
| `staff_cancel_appointment` | "cancel the appointment" | Cancels an appointment |
| `staff_view_my_schedule` | "show my schedule" | Shows the clinician's schedule |
| `staff_page_anesthesiologist` | "page the anesthesiologist" | Pages the anesthesiologist |
| `staff_page_cardiologist` | "page the cardiologist" | Pages the cardiologist |
| `staff_page_neurologist` | "page the neurologist" | Pages the neurologist |

## Ward & Room Control (19)

| Intent | Example | Description |
|---|---|---|
| `ward_call_nurse` | "call a nurse" | Calls a nurse to the room |
| `ward_bed_angle_up` | "raise the bed angle" | Raises the bed head angle |
| `ward_bed_angle_down` | "lower the bed angle" | Lowers the bed head angle |
| `ward_bed_cpr_mode` | "put bed in CPR mode" | Flattens the bed for CPR |
| `ward_mute_monitor_alarm` | "mute the monitor alarm" | Silences the patient monitor alarm |
| `ward_set_monitor_thresholds` | "set monitor thresholds" | Adjusts monitor alarm thresholds |
| `ward_lights_on` | "turn on the room lights" | Turns the room lights on |
| `ward_lights_dim` | "dim the room lights" | Dims the room lights |
| `ward_exam_light_on` | "turn on the exam light" | Turns the examination light on |
| `ward_exam_light_off` | "turn off the exam light" | Turns the examination light off |
| `ward_temp_increase` | "increase the room temperature" | Raises the room temperature |
| `ward_temp_decrease` | "decrease the room temperature" | Lowers the room temperature |
| `ward_blinds_open` | "open the room blinds" | Opens the blinds |
| `ward_blinds_close` | "close the room blinds" | Closes the blinds |
| `ward_tv_on` | "turn on the TV" | Turns the TV on |
| `ward_tv_off` | "turn off the TV" | Turns the TV off |
| `ward_lock_doors` | "lock the ward doors" | Locks the ward doors |
| `ward_unlock_doors` ⚠️ | "unlock the ward doors" | Unlocks the ward doors (confirmation required) |
| `ward_request_cleaning` | "request room cleaning" | Requests room cleaning |

## General (11)

| Intent | Example | Description |
|---|---|---|
| `gen_find_wheelchair` | "find a wheelchair" | Locates the nearest wheelchair |
| `gen_find_stretcher` | "find a stretcher" | Locates the nearest stretcher |
| `gen_call_interpreter` | "call an interpreter" | Calls a language interpreter |
| `gen_request_chaplain` | "request a chaplain" | Requests a chaplain |
| `gen_order_patient_meal` | "order a patient meal" | Orders a meal for the patient |
| `gen_order_staff_meal` | "order a staff meal" | Orders a meal for staff |
| `gen_report_broken_equipment` | "report broken equipment" | Reports faulty equipment |
| `gen_request_it_support` | "request IT support" | Requests IT support |
| `gen_locate_colleague` | "locate a colleague" | Finds a colleague in the hospital |
| `gen_view_hospital_map` | "show hospital map" | Shows the hospital map |
| `gen_check_visiting_hours` | "what are the visiting hours" | Shows visiting hours |

---

**Totals:** EMR 14 · Vitals 7 · Symptoms 4 · Medications 10 · Lab 11 ·
Calculators 3 · Emergency 4 · Guides 5 · OR 10 · Staff 10 · Ward 19 ·
General 11 = **108**

The model also has a service class `none` for out-of-domain speech (small
talk, background conversations) — such phrases are rejected with a re-prompt
instead of being forced onto a real command.

💊 / ⚠️ — critical intents: the app never executes them without an on-screen
"Confirm" tap.

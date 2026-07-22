# MedVoice — 108 голосовых команд

Справочник всех команд, которые распознаёт локальная модель
(`medical_intent.onnx`). Формулировка может быть любой близкой к примеру —
модель обучена на 40 вариантах каждой команды (команды произносятся
по-английски). В прототипе MedVoice команды показателей/симптомов/заметок/карты
реально меняют карту пациента; критичные команды (💊 ⚠️) требуют подтверждения
на экране; остальные распознаются и логируются (точки интеграции с больничной
системой).

English version: [INTENTS_EN.md](INTENTS_EN.md)

## Пакетная диктовка

Одна фраза может содержать несколько команд через запятые/паузы: *«BP 120
over 80, pulse 72, temp is 36.8, sats 97»* запишет четыре показателя разом.
Озвучка — одна сводная фраза; клинические предупреждения и эскалации NEWS2
проговариваются всегда отдельно. Если в середине пакета встретилась
критичная команда, всё после неё пропускается до подтверждения или отмены.

## Диктовка заметки: секции SOAP

В режиме диктовки заметки (📝) заметка структурирована по SOAP. Отдельной
фразой переключается секция — **«go to plan»**, **«start assessment»**,
**«switch to objective»**, **«subjective»** — и дальнейшая диктовка попадает
в неё. Текст, который просто упоминает эти слова («the patient plans to
walk»), добавляется как содержимое, а не переключает секцию.

## Структурированные сестринские ассессменты

Рутинный осмотр «с головы до ног» — огромная доля записей во флоушите, и
большинство из них просто подтверждают норму. MedVoice пишет их голосом
детерминированным матчером (система + статус-фраза), без модели и
переобучения.

| Фраза (по-английски) | Запись |
|---|---|
| **«head to toe within normal limits»** · «all systems normal» | **Все 7 систем в норме одной фразой** |
| «skin within normal limits» · «skin intact» · «neuro WNL» | Эта система в норме |
| «chest clear» · «abdomen unremarkable» · «mobility no issues» | Дыхательная / ЖКТ / Подвижность в норме |
| «skin abnormal, redness on the sacrum» | Кожа с отклонением + находка |
| «skin not intact» · «mobility impaired» | Эта система с отклонением |

Системы: **N**euro, **R**espiratory, **C**ardiovascular, **G**I, **S**kin,
**W**ound, **M**obility — показаны цветными чипами (зелёный норма, красный
отклонение, серый не оценено). Статус-фраза обязательна, поэтому
«respiratory rate 18» остаётся витальным, а «page the neurologist» — командой.
Отклонения зачитываются с подсказкой отмены, «scratch that» отменяет
последний ассессмент, а «assessment status» или «what's abnormal» зачитывают
сводку; отклонения попадают и в SBAR-сводку «catch me up».

## Баланс жидкости (приём / выделение, I/O)

Медсёстры вносят ~640 записей во флоушит за смену, в основном I/O. MedVoice
записывает их голосом детерминированным матчером (ключевое слово категории +
объём) — без модели и переобучения. Карточка «Fluid Balance» показывает
суммарный приём, суммарное выделение и баланс.

| Фраза (по-английски) | Запись |
|---|---|
| «oral intake 200 mL» · «patient drank 250» | Приём — oral |
| «IV fluids 500» | Приём — IV |
| «urine output 300» · «voided 250 mL» | Выделение — urine |
| «drain output 50» · «drainage 40 mils» | Выделение — drain |
| «vomit 100» · «emesis 150 mL» | Выделение — emesis |
| «output 250» / «intake 200» | Общий выход / приём |

Объём принимает миллилитры, литры («1.5 litres» → 1500 мл) и «N thousand».
Записи I/O нужен объём — «urine output» без числа это обзорный запрос (ниже), а
«order IV fluids» (без объёма) остаётся назначением. «scratch that» отменяет
последнюю запись — витальную или жидкости. Спросите **«fluid balance»**,
**«total intake»**, **«total output»** или **«urine output»**, чтобы услышать
суммы; SBAR-сводка «catch me up» включает баланс.

## Голосовой обзор карты (спроси и послушай)

Обзор карты — это чтение, а не ввод, поэтому это отдельный режим: врач
спрашивает, карта отвечает голосом, а озвученный ответ дублируется текстом в
карточке read-back (недослышанный показатель опасен). Ничего не пишется.
Распознаётся точными фразами, без модели. Ничего не выдумывает — при
отсутствии данных отвечает «no X on record».

| Фраза (по-английски) | Ответ |
|---|---|
| «any critical values» · «any red flags» | Показатели с алертами + статус NEWS2 — триаж у койки |
| «catch me up» · «patient summary» · «SBAR» | Устная SBAR-сводка: кто, NEWS2 + тренд, ключевые показатели, план, последнее событие |
| «latest vitals» · «read the vitals» | Все витальные по порядку |
| «what's the blood pressure» · «sats trend» · «latest pulse» | Последнее значение показателя с трендом |
| «what's the NEWS score» | Счёт NEWS2 и уровень риска |
| «read the plan» · «read the assessment» · «read the note» | Секция SOAP или вся заметка |
| «what's been done» · «recap» | Недавняя активность из аудит-лога |

Ответы берутся только из данных, которые MedVoice реально хранит (витальные +
история, NEWS2, SOAP-заметки, аудит). Препараты, лабы и диагнозы
распознаются как команды, но пока не хранятся — их обзор не зачитывает.

## Шкала раннего ухудшения NEWS2

Карта непрерывно считает NEWS2 по пяти голосовым показателям (ЧДД, SpO₂,
температура, систолическое АД, пульс; сознание и кислородная поддержка не
диктуются, поэтому счёт — нижняя оценка). При счёте ≥ 5 приложение
проговаривает *«consider urgent review»*, при ≥ 7 — *«emergency response
required»*. Каждый записанный показатель озвучивается с трендом к
предыдущему значению («down from 94»).

---

## EMR — Электронная медкарта (14)

| Команда | Пример | Описание |
|---|---|---|
| `emr_open_patient_chart` | "open patient **file** for Jane Roe" (устойчиво к акцентам), "open patient chart for…", "switch to patient" | Открывает карту пациента; с именем — сразу переключается на него, без имени — показывает список пациентов |
| `emr_add_patient` | "add a new patient" | Открывает форму нового пациента (имя, MRN, палата) |
| `emr_undo_last` | "**scratch that**" (устойчивый к акцентам шорткат), "undo the last entry" | Удаляет последнюю запись показателя (то же, что шорткат «undo», но ловит развёрнутые формулировки) |
| `emr_create_clinical_note` | "create a new clinical note" | Создаёт новую клиническую заметку |
| `emr_append_to_note` | "add to the note" | Дополняет текущую заметку надиктованным текстом |
| `emr_save_clinical_note` | "save the note" | Сохраняет текущую заметку |
| `emr_dictate_surgery_report` | "start dictating surgery report" | Начинает диктовку протокола операции |
| `emr_add_allergy_warning` | "add allergy warning" | Добавляет предупреждение об аллергии в карту |
| `emr_update_medical_history` | "update the medical history" | Обновляет анамнез пациента |
| `emr_view_past_diagnoses` | "show past diagnoses" | Показывает прошлые диагнозы |
| `emr_search_patient_records` | "find patient records" | Ищет записи пациентов |
| `emr_confirm_patient_identity` | "verify patient identity" | Подтверждает личность пациента (проверка безопасности) |
| `emr_log_patient_consent` | "record patient consent" | Фиксирует информированное согласие пациента |
| `emr_sign_document` ⚠️ | "sign the document" | Подписывает документ электронной подписью (требует подтверждения) |

## Витальные показатели (7)

| Команда | Пример | Описание |
|---|---|---|
| `vitals_log_blood_pressure` | "record blood pressure 120 over 80" | Записывает артериальное давление в карту |
| `vitals_log_heart_rate` | "log heart rate 72" | Записывает пульс |
| `vitals_log_temperature` | "log temperature 38.5" | Записывает температуру тела (°C или °F с конвертацией в °C) |
| `vitals_log_oxygen_saturation` | "oxygen saturation 97" | Записывает сатурацию кислорода (SpO₂) |
| `vitals_log_respiratory_rate` | "log respiratory rate 18" | Записывает частоту дыхания |
| `vitals_log_weight` | "record weight 82 kilograms" | Записывает вес |
| `vitals_display_history` | "show vitals history" | Показывает историю показателей |

## Симптомы (4)

| Команда | Пример | Описание |
|---|---|---|
| `symptom_log_pain_level` | "pain level 7 out of 10" | Записывает уровень боли по шкале 0–10 |
| `symptom_log_fever` | "log fever" | Фиксирует жар/лихорадку как симптом |
| `symptom_log_nausea` | "record nausea" | Фиксирует тошноту |
| `symptom_log_dizziness` | "log dizziness" | Фиксирует головокружение |

## Лекарства (10)

| Команда | Пример | Описание |
|---|---|---|
| `med_prescribe_medication` 💊 | "prescribe a medication" | Выписывает назначение (требует подтверждения) |
| `med_stop_medication` 💊 | "stop the medication" | Отменяет препарат (требует подтверждения) |
| `med_adjust_dosage` 💊 | "adjust the dosage" | Меняет дозировку (требует подтверждения) |
| `med_order_iv_fluids` 💊 | "order IV fluids" | Назначает внутривенные растворы (требует подтверждения) |
| `med_refill_prescription` 💊 | "refill the prescription" | Продлевает рецепт (требует подтверждения) |
| `med_view_current` | "show current medications" | Показывает текущие назначения |
| `med_check_interactions` | "check drug interactions" | Проверяет лекарственные взаимодействия |
| `med_check_allergies` | "check patient allergies" | Проверяет аллергии пациента |
| `med_log_administered` | "log medication administered" | Фиксирует факт введения препарата |
| `med_set_reminder` | "set a medication reminder" | Ставит напоминание о приёме препарата |

## Анализы и исследования (11)

| Команда | Пример | Описание |
|---|---|---|
| `lab_order_blood_test` | "order a blood test" | Назначает анализ крови |
| `lab_order_mri` | "order an MRI" | Назначает МРТ |
| `lab_order_ct_scan` | "order a CT scan" | Назначает КТ |
| `lab_order_xray` | "order an X-ray" | Назначает рентген |
| `lab_order_ultrasound` | "order an ultrasound" | Назначает УЗИ |
| `lab_order_ecg` | "order an ECG" | Назначает ЭКГ |
| `lab_view_results` | "show lab results" | Показывает результаты анализов |
| `lab_check_blood_panel` | "check the blood panel" | Показывает развёрнутый анализ крови |
| `lab_alert_abnormal` | "alert on abnormal labs" | Настраивает оповещение об отклонениях в анализах |
| `lab_share_with_patient` | "share results with patient" | Отправляет результаты пациенту |
| `lab_send_to_specialist` | "send results to specialist" | Пересылает результаты профильному специалисту |

## Калькуляторы (3)

| Команда | Пример | Описание |
|---|---|---|
| `calc_bmi` | "calculate the BMI" | Считает индекс массы тела |
| `calc_glasgow_scale` | "calculate Glasgow coma scale" | Открывает калькулятор шкалы комы Глазго |
| `calc_apgar_score` | "calculate the Apgar score" | Открывает калькулятор шкалы Апгар (новорождённые) |

## Экстренные ситуации (4)

| Команда | Пример | Описание |
|---|---|---|
| `emrgy_call_code_blue` ⚠️ | "call code blue" | Объявляет «код синий» — остановка сердца (требует подтверждения) |
| `emrgy_request_defibrillator` | "request a defibrillator" | Запрашивает дефибриллятор |
| `emrgy_request_blood_units` | "request units of blood" | Запрашивает препараты крови для переливания |
| `emrgy_start_triage` | "start triage" | Запускает протокол сортировки пациентов |

## Протоколы и руководства (5)

| Команда | Пример | Описание |
|---|---|---|
| `guide_view_cpr_steps` | "show CPR steps" | Показывает алгоритм сердечно-лёгочной реанимации |
| `guide_sepsis_protocol` | "show sepsis protocol" | Открывает протокол ведения сепсиса |
| `guide_stroke_protocol` | "show stroke protocol" | Открывает протокол при инсульте |
| `guide_mi_protocol` | "show MI protocol" | Открывает протокол при инфаркте миокарда |
| `guide_check_contraindications` | "check contraindications" | Показывает противопоказания |

## Операционная (10)

| Команда | Пример | Описание |
|---|---|---|
| `or_start_surgery_timer` | "start the surgery timer" | Запускает таймер операции |
| `or_stop_surgery_timer` | "stop the surgery timer" | Останавливает таймер операции |
| `or_log_anesthesia` | "log anesthesia" | Фиксирует анестезию |
| `or_log_implant_serial` | "log the implant serial number" | Записывает серийный номер импланта |
| `or_display_vitals_screen` | "show vitals screen" | Выводит экран мониторинга показателей |
| `or_capture_photo` | "capture a photo" | Делает фото (документация операционного поля) |
| `or_record_video_start` | "start recording" | Начинает запись видео |
| `or_record_video_stop` | "stop recording" | Останавливает запись видео |
| `or_zoom_camera_in` | "zoom the camera in" | Приближает камеру |
| `or_zoom_camera_out` | "zoom the camera out" | Отдаляет камеру |

## Персонал и маршрутизация пациентов (10)

| Команда | Пример | Описание |
|---|---|---|
| `staff_admit_patient` | "admit the patient" | Оформляет госпитализацию пациента |
| `staff_discharge_patient` ⚠️ | "discharge the patient" | Выписывает пациента (требует подтверждения) |
| `staff_transfer_patient_icu` ⚠️ | "transfer to ICU" | Переводит пациента в реанимацию (требует подтверждения) |
| `staff_transfer_patient_ward` ⚠️ | "transfer to ward" | Переводит пациента в отделение (требует подтверждения) |
| `staff_schedule_appointment` | "schedule an appointment" | Записывает на приём |
| `staff_cancel_appointment` | "cancel the appointment" | Отменяет запись на приём |
| `staff_view_my_schedule` | "show my schedule" | Показывает расписание врача |
| `staff_page_anesthesiologist` | "page the anesthesiologist" | Вызывает анестезиолога |
| `staff_page_cardiologist` | "page the cardiologist" | Вызывает кардиолога |
| `staff_page_neurologist` | "page the neurologist" | Вызывает невролога |

## Управление палатой (19)

| Команда | Пример | Описание |
|---|---|---|
| `ward_call_nurse` | "call a nurse" | Вызывает медсестру в палату |
| `ward_bed_angle_up` | "raise the bed angle" | Поднимает изголовье кровати |
| `ward_bed_angle_down` | "lower the bed angle" | Опускает изголовье кровати |
| `ward_bed_cpr_mode` | "put bed in CPR mode" | Переводит кровать в режим СЛР (горизонтально) |
| `ward_mute_monitor_alarm` | "mute the monitor alarm" | Отключает звук тревоги монитора |
| `ward_set_monitor_thresholds` | "set monitor thresholds" | Настраивает пороги тревог монитора |
| `ward_lights_on` | "turn on the room lights" | Включает свет в палате |
| `ward_lights_dim` | "dim the room lights" | Приглушает свет в палате |
| `ward_exam_light_on` | "turn on the exam light" | Включает смотровую лампу |
| `ward_exam_light_off` | "turn off the exam light" | Выключает смотровую лампу |
| `ward_temp_increase` | "increase the room temperature" | Повышает температуру в палате |
| `ward_temp_decrease` | "decrease the room temperature" | Понижает температуру в палате |
| `ward_blinds_open` | "open the room blinds" | Открывает жалюзи |
| `ward_blinds_close` | "close the room blinds" | Закрывает жалюзи |
| `ward_tv_on` | "turn on the TV" | Включает телевизор |
| `ward_tv_off` | "turn off the TV" | Выключает телевизор |
| `ward_lock_doors` | "lock the ward doors" | Блокирует двери отделения |
| `ward_unlock_doors` ⚠️ | "unlock the ward doors" | Разблокирует двери отделения (требует подтверждения) |
| `ward_request_cleaning` | "request room cleaning" | Вызывает уборку палаты |

## Общие запросы (11)

| Команда | Пример | Описание |
|---|---|---|
| `gen_find_wheelchair` | "find a wheelchair" | Находит ближайшее инвалидное кресло |
| `gen_find_stretcher` | "find a stretcher" | Находит ближайшую каталку |
| `gen_call_interpreter` | "call an interpreter" | Вызывает переводчика |
| `gen_request_chaplain` | "request a chaplain" | Вызывает священника/капеллана |
| `gen_order_patient_meal` | "order a patient meal" | Заказывает питание пациенту |
| `gen_order_staff_meal` | "order a staff meal" | Заказывает питание персоналу |
| `gen_report_broken_equipment` | "report broken equipment" | Сообщает о неисправном оборудовании |
| `gen_request_it_support` | "request IT support" | Вызывает IT-поддержку |
| `gen_locate_colleague` | "locate a colleague" | Находит коллегу в больнице |
| `gen_view_hospital_map` | "show hospital map" | Показывает карту больницы |
| `gen_check_visiting_hours` | "what are the visiting hours" | Показывает часы посещения |

---

**Итого:** Медкарта 14 · Показатели 7 · Симптомы 4 · Лекарства 10 ·
Анализы 11 · Калькуляторы 3 · Экстренные 4 · Протоколы 5 · Операционная 10 ·
Персонал 10 · Палата 19 · Общие 11 = **108**

У модели есть также служебный класс `none` для нецелевой речи (болтовня,
фоновые разговоры) — такие фразы отклоняются с переспросом, а не натягиваются
на ближайшую команду.

💊 / ⚠️ — критичные команды: приложение никогда не выполняет их без
подтверждения кнопкой на экране.

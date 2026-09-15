package com.gmailorg.hub

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class FitnessActivity : AppCompatActivity() {

    private enum class Section { TODAY, PLAN, FOOD, SUGGESTIONS, PROGRESS, PROFILE }
    private enum class SessionKind { STRENGTH_A, STRENGTH_B, STRENGTH_C, CARDIO_BASE, CARDIO_INTERVAL, CARDIO_RECOVERY, REST }

    private lateinit var profileSummary: TextView
    private lateinit var sectionTitle: TextView
    private lateinit var sectionBody: TextView
    private lateinit var weekStatus: TextView
    private lateinit var primaryButton: Button
    private lateinit var secondaryButton: Button
    private var section = Section.TODAY
    private var suggestionIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fitness)
        MenuButtonHelper.attach(this)

        profileSummary = findViewById(R.id.fitnessProfileSummary)
        sectionTitle = findViewById(R.id.fitnessSectionTitle)
        sectionBody = findViewById(R.id.fitnessSectionBody)
        weekStatus = findViewById(R.id.fitnessWeekStatus)
        primaryButton = findViewById(R.id.fitnessPrimaryButton)
        secondaryButton = findViewById(R.id.fitnessSecondaryButton)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.fitnessTodayTab).setOnClickListener { showSection(Section.TODAY) }
        findViewById<Button>(R.id.fitnessPlanTab).setOnClickListener { showSection(Section.PLAN) }
        findViewById<Button>(R.id.fitnessFoodTab).setOnClickListener { showSection(Section.FOOD) }
        findViewById<Button>(R.id.fitnessSuggestionsTab).setOnClickListener { showSection(Section.SUGGESTIONS) }
        findViewById<Button>(R.id.fitnessProgressTab).setOnClickListener { showSection(Section.PROGRESS) }
        findViewById<Button>(R.id.fitnessProfileTab).setOnClickListener { showSection(Section.PROFILE) }

        showSection(Section.TODAY)
        if (!FitnessStore.hasProfile(this)) {
            window.decorView.post { showProfileDialog(firstSetup = true) }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun showSection(newSection: Section) {
        section = newSection
        primaryButton.setOnClickListener(null)
        secondaryButton.setOnClickListener(null)
        primaryButton.visibility = View.VISIBLE
        secondaryButton.visibility = View.GONE

        when (newSection) {
            Section.TODAY -> showToday()
            Section.PLAN -> showPlan()
            Section.FOOD -> showFood()
            Section.SUGGESTIONS -> showSuggestion()
            Section.PROGRESS -> showProgress()
            Section.PROFILE -> showProfile()
        }
        refreshStatus()
    }

    private fun requireProfile(): FitnessStore.Profile? {
        val profile = FitnessStore.loadProfile(this)
        if (profile == null) {
            sectionTitle.text = "Maak eerst je fitnessprofiel"
            sectionBody.text = "Vul je geslacht, leeftijd, lengte, gewicht, doel, trainingsdagen en beschikbare apparatuur in. The One gebruikt dat profiel voor je schema, voeding en trainingsvoorstellen."
            primaryButton.text = "Profiel instellen"
            primaryButton.setOnClickListener { showProfileDialog(firstSetup = true) }
            secondaryButton.visibility = View.GONE
        }
        return profile
    }

    private fun showToday() {
        val profile = requireProfile() ?: return
        val today = LocalDate.now()
        val session = weeklySessions(profile)[today.dayOfWeek] ?: SessionKind.REST
        val completed = FitnessStore.isCompleted(this, today)
        sectionTitle.text = "Vandaag • ${today.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale("nl", "NL")))}"
        sectionBody.text = workoutFor(profile, session)

        if (session == SessionKind.REST) {
            primaryButton.text = "Trainingsvoorstel bekijken"
            primaryButton.setOnClickListener { showSection(Section.SUGGESTIONS) }
        } else {
            primaryButton.text = if (completed) "✓ Afgerond — ongedaan maken" else "Training afgerond"
            primaryButton.setOnClickListener {
                FitnessStore.setCompleted(this, !FitnessStore.isCompleted(this, today), today)
                showToday()
                refreshStatus()
            }
        }

        secondaryButton.visibility = View.VISIBLE
        secondaryButton.text = "Gewicht registreren"
        secondaryButton.setOnClickListener { showWeightDialog() }
    }

    private fun showPlan() {
        val profile = requireProfile() ?: return
        val schedule = weeklySessions(profile)
        val dayNames = listOf(
            DayOfWeek.MONDAY to "MAANDAG",
            DayOfWeek.TUESDAY to "DINSDAG",
            DayOfWeek.WEDNESDAY to "WOENSDAG",
            DayOfWeek.THURSDAY to "DONDERDAG",
            DayOfWeek.FRIDAY to "VRIJDAG",
            DayOfWeek.SATURDAY to "ZATERDAG",
            DayOfWeek.SUNDAY to "ZONDAG"
        )
        val plan = buildString {
            append("DOEL\n${goalExplanation(profile.goal)}\n\n")
            append("JOUW WEEK • ${profile.daysPerWeek} TRAININGSDAGEN\n")
            dayNames.forEach { (day, label) ->
                val session = schedule[day] ?: SessionKind.REST
                append("\n$label — ${sessionTitle(profile, session)}\n")
                append(sessionShortDescription(profile, session))
                append("\n")
            }
            append("\nPROGRESSIE\n")
            append("Verhoog eerst herhalingen of duur. Gaat dat 2 trainingen achter elkaar comfortabel en technisch goed, verhoog dan pas rustig gewicht, snelheid of helling. Train kracht meestal met ongeveer 2–3 goede herhalingen over; spierfalen is niet nodig.")
        }
        sectionTitle.text = "Trainingsschema • persoonlijk"
        sectionBody.text = plan
        primaryButton.text = "Naar training van vandaag"
        primaryButton.setOnClickListener { showSection(Section.TODAY) }
        secondaryButton.visibility = View.VISIBLE
        secondaryButton.text = "Profiel / trainingsdagen aanpassen"
        secondaryButton.setOnClickListener { showProfileDialog(firstSetup = false) }
    }

    private fun showFood() {
        val profile = requireProfile() ?: return
        val (lowProtein, highProtein) = proteinRange(profile)
        val goalNote = when (profile.goal) {
            "Afvallen + fitter worden" -> "Werk met kleine, vol te houden portie-aanpassingen. Geen crashdieet: conditie en krachttraining hebben brandstof nodig."
            "Meer spiermassa / sterker" -> "Start rond gewichtsonderhoud en verhoog porties alleen licht als kracht en lichaamsgewicht wekenlang niet vooruitgaan."
            "Conditie verbeteren" -> "Eet voldoende om trainingen energiek te kunnen doen; extreme tekorten helpen je conditie niet."
            else -> "Je hoeft niet te 'bulken'. Gewicht ongeveer stabiel houden terwijl je sterker en fitter wordt is een prima uitgangspunt."
        }
        sectionTitle.text = "Eten • passend bij jouw profiel"
        sectionBody.text = """
            UITGANGSPUNT
            $goalNote
            Op basis van ${formatWeight(profile.weightKg)} kg is een praktische eiwitrichtlijn ongeveer $lowProtein–$highProtein g per dag. Dit is een richtlijn, geen verplicht exact getal.

            ONTBIJT
            Neem een eiwitbron + vezels + fruit. Bijvoorbeeld kwark/Skyr met havermout en fruit, of eieren met brood en fruit.

            LUNCH
            Kies bijvoorbeeld kip, vis, eieren, tofu/tempeh of peulvruchten + brood/rijst/aardappelen + groente.

            TUSSENDOOR
            Denk aan yoghurt/kwark, fruit, melk, noten of een andere simpele eiwitrijke snack.

            AVONDETEN
            Eiwitbron + veel groente + een normale portie rijst, aardappel, pasta of ander koolhydraatproduct.

            RONDOM TRAINING
            • 1–2 uur vooraf: iets lichts met koolhydraten en wat eiwit.
            • Na training: binnen een paar uur een normale maaltijd met eiwit en koolhydraten.

            DRINKEN
            Water is de basis. Drink verspreid over de dag en extra bij warm weer of veel zweten.

            BIJSTUREN
            Kijk niet alleen naar de weegschaal. Let ook op conditie, kracht, energie, slaap en hoe kleding zit. Verander porties klein en beoordeel pas na 2–3 weken het effect.
        """.trimIndent()
        primaryButton.text = "Gewicht registreren"
        primaryButton.setOnClickListener { showWeightDialog() }
        secondaryButton.visibility = View.VISIBLE
        secondaryButton.text = "Profiel aanpassen"
        secondaryButton.setOnClickListener { showProfileDialog(firstSetup = false) }
    }

    private fun showSuggestion() {
        val profile = requireProfile() ?: return
        val suggestions = personalizedSuggestions(profile)
        sectionTitle.text = "Trainingsvoorstel • ${profile.goal.lowercase()}"
        sectionBody.text = suggestions[suggestionIndex % suggestions.size]
        primaryButton.text = "Nieuw voorstel"
        primaryButton.setOnClickListener {
            suggestionIndex = (suggestionIndex + 1) % suggestions.size
            showSuggestion()
        }
        secondaryButton.visibility = View.VISIBLE
        secondaryButton.text = "Naar weekschema"
        secondaryButton.setOnClickListener { showSection(Section.PLAN) }
    }

    private fun showProgress() {
        val profile = requireProfile() ?: return
        val p = FitnessStore.progress(this)
        sectionTitle.text = "Voortgang"
        val dates = if (p.completedDates.isEmpty()) {
            "Nog geen trainingen afgevinkt deze week."
        } else {
            p.completedDates.joinToString("\n") { date ->
                "✓ ${date.format(DateTimeFormatter.ofPattern("EEEE d MMM", Locale("nl", "NL")))}"
            }
        }
        val weightDate = p.weightDate?.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale("nl", "NL"))) ?: "profielwaarde"
        sectionBody.text = """
            DEZE WEEK
            ${p.completedCoreSessions} van ${p.targetCoreSessions} geplande trainingen afgerond.

            $dates

            GEWICHT
            Laatste: ${formatWeight(p.latestWeightKg)} kg • $weightDate

            WAAR JE VOORAL OP LET
            • Je kunt langer of sneller bewegen met dezelfde inspanning.
            • Dezelfde krachtoefening voelt makkelijker of je kunt iets meer herhalingen/gewicht aan.
            • Je herstelt goed tussen trainingen.
            • Je lichaamsbouw verandert in de richting van je doel zonder dat je je futloos voelt.

            HUIDIG DOEL
            ${profile.goal}
        """.trimIndent()
        primaryButton.text = "Gewicht registreren"
        primaryButton.setOnClickListener { showWeightDialog() }
        secondaryButton.visibility = View.VISIBLE
        secondaryButton.text = "Profiel aanpassen"
        secondaryButton.setOnClickListener { showProfileDialog(firstSetup = false) }
    }

    private fun showProfile() {
        val profile = FitnessStore.loadProfile(this)
        sectionTitle.text = "Fitnessprofiel"
        if (profile == null) {
            sectionBody.text = "Nog geen profiel ingesteld."
            primaryButton.text = "Profiel instellen"
            primaryButton.setOnClickListener { showProfileDialog(firstSetup = true) }
            secondaryButton.visibility = View.GONE
            return
        }
        val equipment = equipmentText(profile)
        sectionBody.text = """
            GESLACHT
            ${profile.sex}

            LEEFTIJD
            ${profile.age} jaar

            LENGTE / GEWICHT
            ${formatHeight(profile.heightCm)} • ${formatWeight(profile.weightKg)} kg

            DOEL
            ${profile.goal}

            TRAININGSRITME
            ${profile.daysPerWeek} dagen per week

            APPARATUUR
            $equipment

            Deze gegevens staan alleen lokaal op dit apparaat en worden gebruikt om je Fitness-schema en voedingsrichtlijnen aan te passen.
        """.trimIndent()
        primaryButton.text = "Profiel aanpassen"
        primaryButton.setOnClickListener { showProfileDialog(firstSetup = false) }
        secondaryButton.visibility = View.VISIBLE
        secondaryButton.text = "Gewicht registreren"
        secondaryButton.setOnClickListener { showWeightDialog() }
    }

    private fun refreshStatus() {
        val profile = FitnessStore.loadProfile(this)
        if (profile == null) {
            profileSummary.text = "Nog geen persoonlijk profiel ingesteld"
            weekStatus.text = "Stel je profiel in om een schema te maken."
            return
        }
        profileSummary.text = "${profile.sex} • ${profile.age} jaar • ${formatHeight(profile.heightCm)} • ${formatWeight(profile.weightKg)} kg\n${profile.goal} • ${profile.daysPerWeek} dagen/week • ${equipmentText(profile)}"
        val p = FitnessStore.progress(this)
        weekStatus.text = "Deze week: ${p.completedCoreSessions}/${p.targetCoreSessions} trainingen • laatste gewicht ${formatWeight(p.latestWeightKg)} kg"
    }

    private fun showProfileDialog(firstSetup: Boolean) {
        val existing = FitnessStore.loadProfile(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val fieldGap = (8 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        fun label(text: String) {
            container.addView(TextView(this).apply {
                this.text = text
                setTextColor(ContextCompat.getColor(context, R.color.text_main))
                textSize = 13f
                setPadding(0, fieldGap, 0, 2)
            })
        }

        label("Geslacht")
        val sexOptions = listOf("Niet opgegeven", "Man", "Vrouw", "Anders / liever niet zeggen")
        val sexSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@FitnessActivity, android.R.layout.simple_spinner_dropdown_item, sexOptions)
            setSelection(sexOptions.indexOf(existing?.sex).takeIf { it >= 0 } ?: 0)
        }
        container.addView(sexSpinner)

        label("Leeftijd")
        val ageInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "bijv. 51"
            setText(existing?.age?.toString().orEmpty())
        }
        container.addView(ageInput)

        label("Lengte in cm")
        val heightInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "bijv. 180"
            setText(existing?.heightCm?.toString().orEmpty())
        }
        container.addView(heightInput)

        label("Gewicht in kg")
        val weightInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "bijv. 80"
            setText(existing?.weightKg?.let { formatWeight(it) }.orEmpty())
        }
        container.addView(weightInput)

        label("Doel")
        val goalOptions = listOf(
            "Conditie + spiermassa",
            "Conditie verbeteren",
            "Afvallen + fitter worden",
            "Meer spiermassa / sterker"
        )
        val goalSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@FitnessActivity, android.R.layout.simple_spinner_dropdown_item, goalOptions)
            setSelection(goalOptions.indexOf(existing?.goal).takeIf { it >= 0 } ?: 0)
        }
        container.addView(goalSpinner)

        label("Trainingsdagen per week")
        val dayOptions = listOf("2", "3", "4", "5", "6")
        val daysSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@FitnessActivity, android.R.layout.simple_spinner_dropdown_item, dayOptions)
            val current = (existing?.daysPerWeek ?: 4).toString()
            setSelection(dayOptions.indexOf(current).coerceAtLeast(0))
        }
        container.addView(daysSpinner)

        label("Beschikbare apparatuur")
        val treadmillCheck = CheckBox(this).apply {
            text = "Loopband beschikbaar"
            setTextColor(ContextCompat.getColor(context, R.color.text_main))
            isChecked = existing?.hasTreadmill ?: false
        }
        val gymCheck = CheckBox(this).apply {
            text = "Volledige fitnessruimte / gym beschikbaar"
            setTextColor(ContextCompat.getColor(context, R.color.text_main))
            isChecked = existing?.hasGym ?: false
        }
        container.addView(treadmillCheck)
        container.addView(gymCheck)

        val scroll = ScrollView(this).apply { addView(container) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (firstSetup) "Stel je fitnessprofiel in" else "Fitnessprofiel aanpassen")
            .setMessage("The One maakt hiermee een persoonlijk trainings- en voedingspatroon. Je kunt dit later altijd wijzigen.")
            .setView(scroll)
            .setPositiveButton("Opslaan", null)
            .apply { if (!firstSetup) setNegativeButton("Annuleren", null) }
            .create()
        dialog.setCancelable(!firstSetup)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val age = ageInput.text.toString().trim().toIntOrNull()
                val height = heightInput.text.toString().trim().toIntOrNull()
                val weight = weightInput.text.toString().trim().replace(',', '.').toFloatOrNull()
                val days = dayOptions[daysSpinner.selectedItemPosition].toInt()
                if (age == null || age !in 18..90) {
                    Toast.makeText(this, "Vul een leeftijd tussen 18 en 90 in.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (height == null || height !in 130..220) {
                    Toast.makeText(this, "Vul een geldige lengte in (130–220 cm).", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (weight == null || weight !in 35f..250f) {
                    Toast.makeText(this, "Vul een geldig gewicht in (35–250 kg).", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                FitnessStore.saveProfile(
                    this,
                    FitnessStore.Profile(
                        sex = sexOptions[sexSpinner.selectedItemPosition],
                        age = age,
                        heightCm = height,
                        weightKg = weight,
                        goal = goalOptions[goalSpinner.selectedItemPosition],
                        daysPerWeek = days,
                        hasTreadmill = treadmillCheck.isChecked,
                        hasGym = gymCheck.isChecked
                    )
                )
                dialog.dismiss()
                refreshStatus()
                showSection(if (section == Section.PROFILE) Section.PROFILE else Section.TODAY)
            }
        }
        dialog.show()
    }

    private fun showWeightDialog() {
        val profile = FitnessStore.loadProfile(this) ?: run {
            showProfileDialog(firstSetup = true)
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(formatWeight(profile.weightKg))
            selectAll()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Gewicht registreren")
            .setMessage("Gebruik bij voorkeur steeds dezelfde weegschaal en ongeveer hetzelfde moment van de dag.")
            .setView(input)
            .setPositiveButton("Opslaan", null)
            .setNegativeButton("Annuleren", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim().replace(',', '.').toFloatOrNull()
                if (value == null || value !in 35f..250f) {
                    Toast.makeText(this, "Vul een geldig gewicht in.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                FitnessStore.saveWeight(this, value)
                dialog.dismiss()
                refreshStatus()
                showSection(section)
            }
        }
        dialog.show()
    }

    private fun weeklySessions(profile: FitnessStore.Profile): Map<DayOfWeek, SessionKind> {
        val sessions = linkedMapOf<DayOfWeek, SessionKind>()
        when (profile.daysPerWeek) {
            2 -> {
                sessions[DayOfWeek.MONDAY] = SessionKind.STRENGTH_A
                sessions[DayOfWeek.THURSDAY] = SessionKind.CARDIO_BASE
            }
            3 -> {
                sessions[DayOfWeek.MONDAY] = SessionKind.STRENGTH_A
                sessions[DayOfWeek.WEDNESDAY] = SessionKind.CARDIO_BASE
                sessions[DayOfWeek.SATURDAY] = SessionKind.STRENGTH_B
            }
            4 -> {
                sessions[DayOfWeek.MONDAY] = SessionKind.STRENGTH_A
                sessions[DayOfWeek.TUESDAY] = SessionKind.CARDIO_BASE
                sessions[DayOfWeek.THURSDAY] = SessionKind.STRENGTH_B
                sessions[DayOfWeek.SATURDAY] = SessionKind.CARDIO_INTERVAL
            }
            5 -> {
                sessions[DayOfWeek.MONDAY] = SessionKind.STRENGTH_A
                sessions[DayOfWeek.TUESDAY] = SessionKind.CARDIO_BASE
                sessions[DayOfWeek.THURSDAY] = SessionKind.STRENGTH_B
                sessions[DayOfWeek.FRIDAY] = if (profile.goal == "Meer spiermassa / sterker") SessionKind.STRENGTH_C else SessionKind.CARDIO_RECOVERY
                sessions[DayOfWeek.SATURDAY] = SessionKind.CARDIO_INTERVAL
            }
            else -> {
                sessions[DayOfWeek.MONDAY] = SessionKind.STRENGTH_A
                sessions[DayOfWeek.TUESDAY] = SessionKind.CARDIO_BASE
                sessions[DayOfWeek.WEDNESDAY] = SessionKind.STRENGTH_B
                sessions[DayOfWeek.THURSDAY] = SessionKind.CARDIO_RECOVERY
                sessions[DayOfWeek.FRIDAY] = SessionKind.STRENGTH_C
                sessions[DayOfWeek.SATURDAY] = SessionKind.CARDIO_INTERVAL
            }
        }
        return sessions
    }

    private fun workoutFor(profile: FitnessStore.Profile, session: SessionKind): String {
        val warmup = if (profile.age >= 50) "8–10 min rustig opwarmen" else "5–8 min rustig opwarmen"
        return when (session) {
            SessionKind.STRENGTH_A -> if (profile.hasGym) {
                """FULL BODY A • GYM\n\n$warmup.\nLeg press 3×10–12\nChest press 3×8–12\nLat pulldown 3×8–12\nSeated row 2×10–12\nShoulder press 2×8–12\nPlank 3×30–45 sec\n\nRust 60–90 sec. Stop de meeste sets met ongeveer 2–3 goede herhalingen over.""".replace("\\n", "\n")
            } else {
                """FULL BODY A • THUIS\n\n$warmup.\nSquat naar stoel 3×10–15\nIncline push-up tegen tafel/bank 3×8–12\nGlute bridge 3×12–15\nRugzak-row 3×10–12\nStep-up 2×10/been\nPlank 3×20–45 sec\n\nWerk beheerst en stop voor techniek verslechtert.""".replace("\\n", "\n")
            }
            SessionKind.STRENGTH_B -> if (profile.hasGym) {
                """FULL BODY B • GYM\n\n$warmup.\nLeg curl 3×10–12\nLeg extension of split squat 2×10/been\nIncline chest press 3×8–12\nCable row 3×10–12\nLat pulldown 2×10–12\nWoodchop of dead bug 3 sets\n\nRust 60–90 sec en houd de uitvoering rustig.""".replace("\\n", "\n")
            } else {
                """FULL BODY B • THUIS\n\n$warmup.\nReverse lunge of split squat 3×8–10/been\nIncline push-up 3×8–12\nHip hinge met rugzak 3×10–12\nRugzak-row 3×10–12\nCalf raise 3×12–20\nDead bug 3×8/zijde\n\nNeem 60–90 sec rust tussen sets.""".replace("\\n", "\n")
            }
            SessionKind.STRENGTH_C -> if (profile.hasGym) {
                """FULL BODY C • LICHT/MIDDEL\n\n$warmup.\nGoblet squat of leg press 2×10–12\nMachine chest press 2×10–12\nRow 2×10–12\nRomanian deadlift-machine/kabel 2×10\nLateral raise 2×12–15\nCore 2–3 sets\n\nDeze derde krachtsessie blijft iets lichter dan A en B.""".replace("\\n", "\n")
            } else workoutFor(profile, SessionKind.STRENGTH_A)
            SessionKind.CARDIO_BASE -> cardioBase(profile)
            SessionKind.CARDIO_INTERVAL -> cardioInterval(profile)
            SessionKind.CARDIO_RECOVERY -> """LICHTE CONDITIE / HERSTEL\n\n${cardioMode(profile)} 20–30 min op rustig tempo. Je moet makkelijk kunnen praten. Sluit af met 5–10 min rustige mobiliteit.""".replace("\\n", "\n")
            SessionKind.REST -> """HERSTELDAG\n\nVandaag staat geen kerntraining gepland. Een ontspannen wandeling of 8–10 min mobiliteit is prima. Herstel hoort bij vooruitgang: slaap, eet normaal en forceer geen extra zware training omdat je 'een dag mist'.""".replace("\\n", "\n")
        }
    }

    private fun cardioBase(profile: FitnessStore.Profile): String {
        val mode = cardioMode(profile)
        return """BASISCONDITIE • $mode\n\n5 min rustig opwarmen\n20–30 min stevig, gelijkmatig tempo\n5 min rustig uitlopen\n\nIntensiteit: je kunt nog in korte zinnen praten. Totaal ongeveer 30–40 min.""".replace("\\n", "\n")
    }

    private fun cardioInterval(profile: FitnessStore.Profile): String {
        val mode = cardioMode(profile)
        val ageNote = if (profile.age >= 50) "Begin desnoods met stevig wandelen; joggen is alleen nodig als dat comfortabel voelt." else "Verhoog eerst tempo of helling, niet alles tegelijk."
        return """INTERVALLEN • $mode\n\n5–8 min warm-up\n6×: 1 min vlot + 90 sec rustig\n5 min cool-down\n\n$ageNote De snelle minuten zijn stevig, maar niet maximaal.""".replace("\\n", "\n")
    }

    private fun cardioMode(profile: FitnessStore.Profile): String = when {
        profile.hasTreadmill -> "LOOPBAND"
        profile.hasGym -> "CARDIOMACHINE / LOOPBAND"
        else -> "WANDELEN / FIETSEN BUITEN"
    }

    private fun sessionTitle(profile: FitnessStore.Profile, session: SessionKind): String = when (session) {
        SessionKind.STRENGTH_A -> if (profile.hasGym) "Full body A • gym" else "Full body A • thuis"
        SessionKind.STRENGTH_B -> if (profile.hasGym) "Full body B • gym" else "Full body B • thuis"
        SessionKind.STRENGTH_C -> if (profile.hasGym) "Full body C • licht" else "Full body • thuis"
        SessionKind.CARDIO_BASE -> "Basisconditie • ${cardioMode(profile).lowercase()}"
        SessionKind.CARDIO_INTERVAL -> "Intervallen • ${cardioMode(profile).lowercase()}"
        SessionKind.CARDIO_RECOVERY -> "Lichte conditie / herstel"
        SessionKind.REST -> "Herstel / rust"
    }

    private fun sessionShortDescription(profile: FitnessStore.Profile, session: SessionKind): String = when (session) {
        SessionKind.STRENGTH_A, SessionKind.STRENGTH_B -> "45–55 min kracht, hele lichaam. Goede techniek en rustige progressie."
        SessionKind.STRENGTH_C -> "30–45 min lichtere derde krachtsessie."
        SessionKind.CARDIO_BASE -> "30–40 min gelijkmatig tempo; nog kunnen praten."
        SessionKind.CARDIO_INTERVAL -> "25–30 min inclusief korte, gecontroleerde intervallen."
        SessionKind.CARDIO_RECOVERY -> "20–30 min zeer rustig bewegen + mobiliteit."
        SessionKind.REST -> "Volledige rust of ontspannen wandelen/mobiliteit."
    }

    private fun personalizedSuggestions(profile: FitnessStore.Profile): List<String> {
        val strengthExpress = if (profile.hasGym) {
            "30-MINUTEN GYM EXPRESS\n3 rondes: leg press 10–12 • chest press 10–12 • lat pulldown 10–12 • cable row 10–12 • plank 30 sec. Rust 60–90 sec tussen rondes."
        } else {
            "25-MINUTEN THUIS FULL BODY\n3 rondes: stoel-squat 12 • incline push-up 8–12 • glute bridge 15 • rugzak-row 10–12 • plank 30 sec. Rust 60 sec tussen rondes."
        }
        val cardio = if (profile.hasTreadmill) {
            "20-MINUTEN LOOPBAND\n5 min rustig • 10 min stevig wandelen op lichte helling • 5 min rustig. Prima op een drukke dag."
        } else {
            "25-MINUTEN BUITENCONDITIE\n5 min rustig wandelen • 15 min stevig wandelen of fietsen • 5 min rustig. Houd een tempo waarbij je nog kunt praten."
        }
        val combo = if (profile.hasGym) {
            "CONDITIE + KRACHT COMBI\n10 min cardio • 2 sets leg press • 2 sets chest press • 2 sets row • 2 sets pulldown • 10 min cardio. Totaal ±40 min."
        } else {
            "CONDITIE + KRACHT COMBI\n10 min wandelen • 3 rondes squat 12 + incline push-up 10 + glute bridge 15 + plank 30 sec • 10 min wandelen."
        }
        return listOf(
            strengthExpress,
            cardio,
            combo,
            "MOBILITEIT / HERSTEL\n2 rustige rondes: heupbuiger stretch 30 sec/zijde • borstopening 30 sec • calf stretch 30 sec/zijde • 8 squats • 8 wall slides • 5 min wandelen.",
            "INTERVAL LIGHT\n5 min warm-up • 5× (1 min flink tempo + 2 min rustig) • 5 min cool-down. Stop ruim voor maximale inspanning."
        )
    }

    private fun proteinRange(profile: FitnessStore.Profile): Pair<Int, Int> {
        val factors = when (profile.goal) {
            "Meer spiermassa / sterker" -> 1.6f to 1.8f
            "Afvallen + fitter worden" -> 1.5f to 1.7f
            "Conditie verbeteren" -> 1.2f to 1.6f
            else -> 1.4f to 1.6f
        }
        return (profile.weightKg * factors.first).roundToInt() to (profile.weightKg * factors.second).roundToInt()
    }

    private fun goalExplanation(goal: String): String = when (goal) {
        "Conditie verbeteren" -> "Meer uithoudingsvermogen, met genoeg krachttraining om spieren en belastbaarheid te onderhouden."
        "Afvallen + fitter worden" -> "Conditie en kracht verbeteren terwijl voeding rustig op een haalbaar energietekort wordt afgestemd."
        "Meer spiermassa / sterker" -> "Geleidelijk sterker worden en spiermassa opbouwen met voldoende herstel en conditiewerk."
        else -> "Meer conditie, een atletischer/sterker lichaam en geleidelijk wat spiermassa zonder bodybuilding-volume."
    }

    private fun equipmentText(profile: FitnessStore.Profile): String = when {
        profile.hasTreadmill && profile.hasGym -> "loopband + volledige fitnessruimte"
        profile.hasTreadmill -> "loopband"
        profile.hasGym -> "volledige fitnessruimte"
        else -> "geen vaste apparatuur; thuis/buiten"
    }

    private fun formatHeight(cm: Int): String = String.format(Locale("nl", "NL"), "%.2f m", cm / 100.0)

    private fun formatWeight(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.US, "%.1f", value).replace('.', ',')
}

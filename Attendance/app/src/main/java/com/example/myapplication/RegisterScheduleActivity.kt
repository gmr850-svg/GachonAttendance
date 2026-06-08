package com.example.myapplication

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.model.domain.model.Subject
import com.example.myapplication.model.domain.model.Period
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class RegisterScheduleActivity : AppCompatActivity() {

    private lateinit var etCourseCode: EditText
    private lateinit var btnAddClass: Button
    private lateinit var btnConfirmSchedule: Button
    private lateinit var classBlockLayer: FrameLayout

    private val selectedSubjects = mutableListOf<Subject>()
    private var userId: String = ""

    private val database = FirebaseDatabase.getInstance().reference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.register_schedule)

        userId = getSharedPreferences("LOGIN_INFO", MODE_PRIVATE).getString("userId", "") ?: ""

        etCourseCode = findViewById(R.id.etCourseCode)
        btnAddClass = findViewById(R.id.btnAddClass)
        btnConfirmSchedule = findViewById(R.id.btnConfirmSchedule)
        classBlockLayer = findViewById(R.id.classBlockLayer)

        btnAddClass.setOnClickListener {
            val inputCode = etCourseCode.text.toString().trim()
            if (inputCode.isEmpty()) {
                Toast.makeText(this, "과목 코드를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lookupAndAddSubject(inputCode)
        }

        btnConfirmSchedule.setOnClickListener {
            if (selectedSubjects.isEmpty()) {
                Toast.makeText(this, "최소 1개 이상의 수업을 추가해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (userId.isBlank()) {
                Toast.makeText(this, "로그인 정보가 없습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveEnrollmentToFirebase()
        }
    }

    private fun lookupAndAddSubject(subjectCode: String) {
        lifecycleScope.launch {
            try {
                val snapshot = database.child("Subjects").child(subjectCode).get().await()
                val subject = snapshot.getValue(Subject::class.java)

                if (subject == null) {
                    Toast.makeText(this@RegisterScheduleActivity, "등록되지 않은 과목 코드입니다.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                if (selectedSubjects.any { it.subjectCode == subject.subjectCode }) {
                    Toast.makeText(this@RegisterScheduleActivity, "이미 추가된 과목입니다.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 시간 겹침 체크
                if (isTimeOverlapping(subject, selectedSubjects)) {
                    Toast.makeText(this@RegisterScheduleActivity, "시간표가 겹치는 과목이 있습니다.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                selectedSubjects.add(subject)
                drawSubjectBlock(subject, selectedSubjects.size - 1)

                etCourseCode.text.clear()
                Toast.makeText(this@RegisterScheduleActivity, "${subject.subjectName} 추가됨", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Toast.makeText(this@RegisterScheduleActivity, "오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isTimeOverlapping(newSubject: Subject, currentSubjects: List<Subject>): Boolean {
        for (newDayEntry in newSubject.schedule) {
            val newDaySchedule = newDayEntry.value
            for (currentSubject in currentSubjects) {
                for (currentDayEntry in currentSubject.schedule) {
                    val currentDaySchedule = currentDayEntry.value
                    
                    if (isSameDay(newDaySchedule.dayOfWeek, currentDaySchedule.dayOfWeek)) {
                        for (newPeriod in newDaySchedule.periods) {
                            if (newPeriod == null) continue
                            for (currentPeriod in currentDaySchedule.periods) {
                                if (currentPeriod == null) continue
                                if (checkOverlap(newPeriod, currentPeriod)) {
                                    return true
                                }
                            }
                        }
                    }
                }
            }
        }
        return false
    }

    private fun isSameDay(day1: String, day2: String): Boolean {
        val d1 = day1.lowercase()
        val d2 = day2.lowercase()
        // 요일 표기 형식이 다를 수 있으므로 변환 처리 (예: Monday <-> 월)
        val normalized1 = normalizeDay(d1)
        val normalized2 = normalizeDay(d2)
        return normalized1 == normalized2
    }

    private fun normalizeDay(day: String): String {
        return when (day) {
            "monday", "월", "월요일" -> "mon"
            "tuesday", "화", "화요일" -> "tue"
            "wednesday", "수", "수요일" -> "wed"
            "thursday", "목", "목요일" -> "thu"
            "friday", "금", "금요일" -> "fri"
            "saturday", "토", "토요일" -> "sat"
            "sunday", "일", "일요일" -> "sun"
            else -> day
        }
    }

    private fun checkOverlap(p1: Period, p2: Period): Boolean {
        return p1.startTime < p2.endTime && p2.startTime < p1.endTime
    }

    private fun saveEnrollmentToFirebase() {
        lifecycleScope.launch {
            try {
                for (subject in selectedSubjects) {
                    database.child("Enrollment").child(userId).child(subject.subjectCode).setValue(true).await()
                }
                Toast.makeText(this@RegisterScheduleActivity, "시간표가 저장되었습니다.", Toast.LENGTH_SHORT).show()

                startActivity(Intent(this@RegisterScheduleActivity, MainActivity::class.java))
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@RegisterScheduleActivity, "저장에 실패했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun drawSubjectBlock(subject: Subject, index: Int) {
        val cleanName = subject.subjectName.replace(" (영어강의)", "").replace(" (실시간화상강의)", "")

        val colors = listOf("#8FA2C7", "#B9AAA5", "#79B2B8", "#A7B58D", "#C39DA4")
        val color = colors[index % colors.size]

        for ((_, daySchedule) in subject.schedule) {
            val location = daySchedule.location.ifEmpty { "미정" }
            val dayKr = when (normalizeDay(daySchedule.dayOfWeek.lowercase())) {
                "mon" -> "월"; "tue" -> "화"; "wed" -> "수"; "thu" -> "목"; "fri" -> "금"; else -> continue
            }

            for (period in daySchedule.periods) {
                if (period == null) continue

                val startHour = period.startTime.substringBefore(":").toIntOrNull() ?: continue
                val endHour = period.endTime.substringBefore(":").toIntOrNull() ?: continue

                val block = TextView(this).apply {
                    text = "$cleanName\n$location"
                    setTextColor(Color.WHITE)
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.parseColor(color))
                    setPadding(4, 4, 4, 4)
                }

                val columnWidth = if (classBlockLayer.width > 0) classBlockLayer.width / 5 else (resources.displayMetrics.widthPixels - 200) / 5
                val oneHourHeight = (52 * resources.displayMetrics.density).toInt()

                val params = FrameLayout.LayoutParams(
                    columnWidth,
                    (endHour - startHour).coerceAtLeast(1) * oneHourHeight
                ).apply {
                    leftMargin = columnWidth * when (dayKr) { "월" -> 0; "화" -> 1; "수" -> 2; "목" -> 3; "금" -> 4; else -> 0 }
                    topMargin = oneHourHeight * when (startHour) { in 9..16 -> startHour - 9; else -> 0 }
                }

                classBlockLayer.addView(block, params)
            }
        }
    }
}
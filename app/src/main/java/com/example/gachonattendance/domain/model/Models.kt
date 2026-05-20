package com.example.gachonattendance.domain.model

data class User(
    val email: String = "",
    val name: String = "",
    val password: String = "",
    val portalID: String = "",
    val userId: String = "",
    val userType: String = ""
)

data class Subject(
    val subjectCode: String = "",
    val subjectName: String = "",
    val professorName: String = "",
    val schedule: Map<String, DaySchedule> = emptyMap()
)

data class DaySchedule(
    val dayOfWeek: String = "",
    val location: String = "",
    val periods: List<Period?> = emptyList()
)

data class Period(
    val startTime: String = "",
    val endTime: String = ""
)

data class AttendanceSession(
    val authMethod: String = "",
    val pinCode: Int = 0,
    val status: String = ""
)

data class AttendanceRecord(
    val finalStatus: String = "",
    val missedCount: Int = 0
)


data class UwbLog(
    val isDetected: Boolean = false,
    val timestamp: String = ""
)

data class AbsenceRequest(
    val absenceType: String = "",
    val content: String = "",
    val status: String = "PENDING",
    val timestamp: Long = 0L,
    val evidenceUrl: String = ""
)

data class ClassNotice(
    val noticeId: String = "",
    val type: String = "NORMAL",   // CANCELED, MAKEUP
    val targetDate: String = "",   // 해당되는 날짜
    val title: String = "",
    val content: String = "",
    val timestamp: Long = 0L
)
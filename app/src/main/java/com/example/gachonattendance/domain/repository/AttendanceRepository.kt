package com.example.gachonattendance.domain.repository

import com.example.gachonattendance.domain.model.Subject
import com.example.gachonattendance.domain.model.AttendanceSession
import com.example.gachonattendance.domain.model.AbsenceRequest
import com.example.gachonattendance.domain.model.ClassNotice
import kotlinx.coroutines.flow.Flow

interface AttendanceRepository {
    // [공통] 전체 과목 리스트 가져오는 함수
    suspend fun getSubjects(): List<Subject>

    // [학생용] 내가 수강 신청한 과목만 가져오는 함수
    suspend fun getEnrolledSubjects(studentId: String): List<Subject>

    // [학생용] 실시간으로 현재 출결 세션 상태(READY/CLOSED 여부, PIN)를 확인하는 함수
    fun getSessionStatus(subjectCode: String, date: String): Flow<AttendanceSession?>

    // [학생용] UWB 측정 거리 및 입력한 PIN 로그 제출하는 함수
    suspend fun submitUwbLog(subjectCode: String, date: String, studentId: String,
                             timeSlot: String, isDetected: Boolean, timeString: String): Boolean

    // [학생용] 내 출결 기록(출석/지각 등) 실시간 감지하는 함수
    fun getMyAttendanceStatus(subjectCode: String, date: String, studentId: String): Flow<String?>

    // [교수용] 출결 세션(PIN 번호) 여는 함수 (READY)
    suspend fun openAttendanceSession(subjectCode: String, date: String, pinCode: Int): Boolean

    // [교수용] 출결 세션 종료하는 함수 (CLOSED)
    suspend fun closeAttendanceSession(subjectCode: String, date: String): Boolean

    // [학생용] 인증결석 신청서를 제출하는 함수
    suspend fun submitAbsenceRequest(
        subjectCode: String,
        date: String,
        studentId: String,
        absenceType: String,
        content: String,
        evidenceUrl: String
    ): Boolean

    // [학생용] 인증결석 신청 상태 실시간 조회 함수
    fun getAbsenceRequestStatus(
        subjectCode: String,
        date: String,
        studentId: String
    ): Flow<AbsenceRequest?>

    // [교수용] 강의 공지/휴강/보강 등록
    suspend fun postClassNotice(
        subjectCode: String,
        type: String, // "CANCELED", "MAKEUP"
        targetDate: String,
        title: String,
        content: String
    ): Boolean

    // [공통] 해당 과목의 모든 공지사항 가져오는 함수 (게시판용)
    suspend fun getClassNotices(subjectCode: String): List<ClassNotice>

    // [학생용] 오늘이 휴강인지 확인하는 함수
    suspend fun isClassCanceled(subjectCode: String, date: String): Boolean
}

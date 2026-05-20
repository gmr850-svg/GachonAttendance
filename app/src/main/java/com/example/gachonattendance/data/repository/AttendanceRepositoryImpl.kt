package com.example.gachonattendance.data.repository

import com.example.gachonattendance.domain.model.AttendanceSession
import com.example.gachonattendance.domain.model.Subject
import com.example.gachonattendance.domain.model.UwbLog
import com.example.gachonattendance.domain.repository.AttendanceRepository
import com.example.gachonattendance.domain.model.AbsenceRequest
import com.example.gachonattendance.domain.model.ClassNotice
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class AttendanceRepositoryImpl : AttendanceRepository {

    private val database = FirebaseDatabase.getInstance("https://attendanceapp-cbf00-default-rtdb.asia-southeast1.firebasedatabase.app/").reference

    // 1. 전체 과목 리스트 가져오는 함수
    override suspend fun getSubjects(): List<Subject> {
        return try {
            val snapshot = database.child("Subjects").get().await()
            val subjectList = mutableListOf<Subject>()
            for (child in snapshot.children) {
                val subject = child.getValue(Subject::class.java)
                if (subject != null) {
                    subjectList.add(subject)
                }
            }
            subjectList
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // 2. 학생의 수강신청 정보(Enrollment)를 기반으로 수강중인 과목만 찾는 함수
    override suspend fun getEnrolledSubjects(studentId: String): List<Subject> {
        return try {
            // "Enrollment/학번" 경로에서 수강 중인 과목 코드 리스트를 가져옴
            val enrollmentSnapshot = database.child("Enrollment").child(studentId).get().await()
            val enrolledSubjectCodes = mutableListOf<String>()

            for (child in enrollmentSnapshot.children) {
                if (child.value == true) { // 수강 중인 게 맞다면 코드 저장
                    child.key?.let { enrolledSubjectCodes.add(it) }
                }
            }

            // 가져온 과목 코드를 바탕으로 Subjects 노드에서 실제 과목 데이터들만 채워넣기
            val subjectList = mutableListOf<Subject>()
            for (code in enrolledSubjectCodes) {
                val subjectSnapshot = database.child("Subjects").child(code).get().await()
                val subject = subjectSnapshot.getValue(Subject::class.java)
                if (subject != null) {
                    subjectList.add(subject)
                }
            }
            subjectList
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // 3. [학생용] 실시간 세션 정보를 확인하는 함수
    override fun getSessionStatus(subjectCode: String, date: String): Flow<AttendanceSession?> = callbackFlow {
        val ref = database.child("Attendance_Session").child(subjectCode).child(date)
        val listener = ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val session = snapshot.getValue(AttendanceSession::class.java)
                trySend(session)
            }
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        })
        awaitClose { ref.removeEventListener(listener) }
    }

    // 4. [학생용] 수집한 UWB 데이터 및 PIN 번호 로그를 제출하는 함수
    override suspend fun submitUwbLog(
        subjectCode: String,
        date: String,
        studentId: String,
        timeSlot: String,
        isDetected: Boolean,
        timeString: String
    ): Boolean {
        return try {
            val logData = UwbLog(
                isDetected = isDetected,
                timeString
            )

            database.child("UWB_Logs")
                .child(subjectCode)
                .child(date)
                .child(studentId)
                .child(timeSlot)
                .setValue(logData)
                .await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // 5. [학생용] 내 최종 출결 기록(finalStatus)을 실시간 감지하는 함수
    override fun getMyAttendanceStatus(subjectCode: String, date: String, studentId: String): Flow<String?> = callbackFlow {
        val ref = database.child("Attendance_Records")
            .child(subjectCode)
            .child(date)
            .child(studentId)
            .child("finalStatus")

        val listener = ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.getValue(String::class.java)
                trySend(status) // "출석", "지각" 등의 값이 바뀌면 UI로 쏴줌
            }
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        })
        awaitClose { ref.removeEventListener(listener) }
    }

    // 6. [교수용] 출결 세션 여는 함수 (status = READY)
    override suspend fun openAttendanceSession(subjectCode: String, date: String, pinCode: Int): Boolean {
        return try {
            val sessionData = AttendanceSession(
                status = "READY",
                authMethod = "BLUETOOTH",
                pinCode = pinCode
            )
            database.child("Attendance_Session")
                .child(subjectCode)
                .child(date)
                .setValue(sessionData)
                .await()
            true
        } catch (e: Exception) {
            false
        }
    }

    // 7. [교수용] 출결 세션 종료하는 함수 (status = CLOSED)
    override suspend fun closeAttendanceSession(subjectCode: String, date: String): Boolean {
        return try {
            // status 필드만 CLOSED로 업데이트
            database.child("Attendance_Session")
                .child(subjectCode)
                .child(date)
                .child("status")
                .setValue("CLOSED")
                .await()
            true
        } catch (e: Exception) {
            false
        }
    }

    // 8. [학생용] 인증 출석 게시판에 작성한 내용 데이터 베이스에 저장하는 함수
    override suspend fun submitAbsenceRequest(
        subjectCode: String,
        date: String,
        studentId: String,
        absenceType: String,
        content: String,
        evidenceUrl: String
    ): Boolean {
        return try {
            val requestData = AbsenceRequest(
                absenceType = absenceType,
                content = content,
                status = "PENDING",
                timestamp = System.currentTimeMillis(),
                evidenceUrl = evidenceUrl
            )

            database.child("Absence_Requests")
                .child(subjectCode)
                .child(date)
                .child(studentId)
                .setValue(requestData)
                .await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // 9. [학생용] 인증결석 신청 상태 실시간 조회 함수
    override fun getAbsenceRequestStatus(
        subjectCode: String,
        date: String,
        studentId: String
    ): Flow<AbsenceRequest?> = callbackFlow {
        val ref = database.child("Absence_Requests")
            .child(subjectCode)
            .child(date)
            .child(studentId)

        val listener = ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val request = snapshot.getValue(AbsenceRequest::class.java)
                trySend(request) // DB 데이터가 변경되면 실시간으로 UI에 쏴줌
            }
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        })
        awaitClose { ref.removeEventListener(listener) }
    }

    // 10. [교수용] 강의 공지/휴강/보강 등록
    override suspend fun postClassNotice(
        subjectCode: String,
        type: String,
        targetDate: String,
        title: String,
        content: String
    ): Boolean {
        return try {
            val timestamp = System.currentTimeMillis()
            val noticeId = "notice_$timestamp" // 고유 ID 생성

            val notice = ClassNotice(
                noticeId = noticeId,
                type = type,
                targetDate = targetDate,
                title = title,
                content = content,
                timestamp = timestamp
            )

            database.child("Class_Notices")
                .child(subjectCode)
                .child(noticeId)
                .setValue(notice)
                .await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // 11. [공통] 해당 과목의 게시판 목록 가져오는 함수 (최신순 정렬)
    override suspend fun getClassNotices(subjectCode: String): List<ClassNotice> {
        return try {
            val snapshot = database.child("Class_Notices").child(subjectCode).get().await()
            val noticeList = mutableListOf<ClassNotice>()
            for (child in snapshot.children) {
                val notice = child.getValue(ClassNotice::class.java)
                if (notice != null) {
                    noticeList.add(notice)
                }
            }
            // 최신 글이 위로 오도록 내림차순 정렬
            noticeList.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // 12. [학생용] 오늘이 휴강인지 확인하는 함수 (메인 화면 UI 처리용)
    override suspend fun isClassCanceled(subjectCode: String, date: String): Boolean {
        return try {
            val notices = getClassNotices(subjectCode)
            // 가져온 공지사항 중, 타겟 날짜가 오늘이고 타입이 "CANCELED"인 항목이 있는지 확인
            val canceledNotice = notices.find { it.targetDate == date && it.type == "CANCELED" }

            // canceledNotice가 존재하면 true(휴강) 반환함
            canceledNotice != null
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
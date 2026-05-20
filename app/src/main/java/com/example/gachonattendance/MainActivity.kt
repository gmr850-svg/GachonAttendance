package com.example.gachonattendance

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gachonattendance.data.repository.AttendanceRepositoryImpl
import com.example.gachonattendance.domain.repository.AttendanceRepository
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val repository: AttendanceRepository = AttendanceRepositoryImpl()
    private val TAG = "FIREBASE_TEST"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // 혹시 xml 파일명이 다르면 알맞게 수정하세요.

        Log.d(TAG, "📱 앱이 켜졌습니다. 테스트를 시작합니다!")

        lifecycleScope.launch {

            // [테스트 1]전체 과목 가져오기 테스트
            testFetchSubjects()

            // [교수용] 출석 세션 열기 테스트
            testOpenSession()

            // [학생용] 실시간 출결 상태 변경 감지 테스트
            testObserveSessionStatus()
        }
    }

    // [테스트 1] 데이터베이스에서 과목들을 잘 읽어오는지 확인
    private suspend fun testFetchSubjects() {
        Log.d(TAG, "🔍 [테스트 1] 과목 정보 가져오는 중...")
        val subjects = repository.getSubjects()
        if (subjects.isNotEmpty()) {
            Log.d(TAG, "✅ [테스트 1] 과목 읽기 성공! 가져온 과목 개수: ${subjects.size}개")
            for (subject in subjects) {
                Log.d(TAG, "👉 과목명: ${subject.subjectName} (교수: ${subject.professorName})")
            }
        } else {
            Log.e(TAG, "❌ [테스트 1] 과목 읽기 실패! (DB가 비어있거나 권한 에러일 수 있습니다)")
        }
    }

    // [테스트 2] Firebase에 데이터를 잘 쓰는지(저장하는지) 확인
    private suspend fun testOpenSession() {
        Log.d(TAG, "📝 [테스트 2] 출석 세션 생성 시도 중...")
        // 테스트용 과목코드(14454001)와 날짜, 임의의 PIN(5678)을 보냅니다.
        val isSuccess = repository.openAttendanceSession(
            subjectCode = "14454001",
            date = "2026-05-13",
            pinCode = 1238
        )
        if (isSuccess) {
            Log.d(TAG, "✅ [테스트 2] 출석 세션 생성 성공! Firebase 콘솔을 확인해 보세요.")
        } else {
            Log.e(TAG, "❌ [테스트 2] 세션 생성 실패!")
        }
    }

    // [테스트 3] 실시간으로 값이 바뀌는 걸 감시하는지 확인
    private fun testObserveSessionStatus() {
        Log.d(TAG, "👀 [테스트 3] 실시간 세션 감시 시작...")
        lifecycleScope.launch {
            repository.getSessionStatus("14454001", "2026-05-13").collect { session ->
                if (session != null) {
                    Log.d(TAG, "🔔 [실시간 감지] 세션 상태가 변경됨! 상태: ${session.status}, PIN: ${session.pinCode}")
                } else {
                    Log.d(TAG, "🔔 [실시간 감지] 데이터가 아직 없습니다.")
                }
            }
        }
    }
}
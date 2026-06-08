package com.example.myapplication

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.launcher.AttendanceServiceLauncher
import com.example.myapplication.model.data.repository.AttendanceRepositoryImpl
import com.example.myapplication.model.domain.model.Subject
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var contentFrame: FrameLayout

    private val repository = AttendanceRepositoryImpl()

    private var currentPageResId: Int = R.layout.main1
    private var userId: String = ""
    private var userName: String = ""
    private var userRole: String = "student"
    private var currentSubjectCode: String = ""

    private var currentClassName: String = "모바일 프로그래밍"
    private var currentClassTime: String = "10:00 ~ 10:50"
    private var currentClassStartTime: String = "10:00"

    private val handler = Handler(Looper.getMainLooper())
    private var pinPopupShowing = false
    private var uwbRunnable: Runnable? = null

    private var attendanceRecordListener: ValueEventListener? = null
    private var attendanceSessionListener: ValueEventListener? = null
    private var activeRecordRef: DatabaseReference? = null
    private var activeSessionRef: DatabaseReference? = null

    /** 출석 Service trigger + 권한 흐름 + Service→Activity broadcast 수신 헬퍼. */
    private lateinit var launcher: AttendanceServiceLauncher

    /** 페이즈 UI 전환(15분 후 After15+UWB 카드) 클라이언트 timer. */
    private var phaseTransitionRunnable: Runnable? = null

    companion object {
        private const val DEFAULT_SUBJECT_CODE = "14454001"
        private const val BLUE_ACTIVE = "#0281F6"
        private const val GRAY_INACTIVE = "#9E9EA4"
        private const val FIVE_MINUTES = 5 * 60 * 1000L
        private const val TEN_MINUTES = 10 * 60 * 1000L
        private const val FIFTEEN_MINUTES = 15 * 60 * 1000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        readLoginInfo()
        if (userId.isNotBlank()) {
            com.example.myapplication.schedule.work.ScheduleSyncWorker.enqueueOnce(this, userId)
            com.example.myapplication.schedule.work.ScheduleSyncWorker.enqueuePeriodic(this, userId)
        }

        setContentView(R.layout.activity_drawer_host)

        drawerLayout = findViewById(R.id.drawerLayout)
        contentFrame = findViewById(R.id.contentFrame)

        launcher = AttendanceServiceLauncher(this)
        launcher.setListener(sessionListener)
        launcher.requestStartupPermissions()

        if (userRole == "professor") {
            loadPage(R.layout.main_p_1)
        } else {
            loadPage(R.layout.main1)
        }

        setupDrawerMenuClick()
    }

    override fun onResume() {
        super.onResume()
        launcher.registerReceiver()
    }

    override fun onPause() {
        super.onPause()
        launcher.unregisterReceiver()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        launcher.handlePermissionResult(requestCode, grantResults)
    }

    override fun onDestroy() {
        super.onDestroy()
        uwbRunnable?.let { handler.removeCallbacks(it) }
        phaseTransitionRunnable?.let { handler.removeCallbacks(it) }
        removeRecordListener()
        removeSessionListener()
    }

    private val sessionListener = object : AttendanceServiceLauncher.SessionEventsListener {
        override fun onSessionStarted(sessionCode: String?, lectureSessionId: String?) {
            val pageView = contentFrame.getChildAt(0) ?: return
            showPin(pageView, sessionCode ?: "")
            Toast.makeText(this@MainActivity, "출석체크가 시작되었습니다 (PIN: $sessionCode)", Toast.LENGTH_SHORT).show()
        }

        override fun onSessionFailed(reason: String?) {
            Toast.makeText(this@MainActivity, "출석 시작 실패: $reason", Toast.LENGTH_LONG).show()
        }

        override fun onSessionExpired() {
            Toast.makeText(this@MainActivity, "BLE 광고 종료 (PIN 수동 입력 계속 가능)", Toast.LENGTH_SHORT).show()
        }

        override fun onAttendanceConfirmed(sessionCode: String?) {
            val pageView = contentFrame.getChildAt(0) ?: return
            updateStudentAttendanceUi(pageView, "출석 완료", true)
            Toast.makeText(this@MainActivity, "출석 처리되었습니다", Toast.LENGTH_SHORT).show()
        }

        override fun onAttendanceFailed(reason: String?) {
            Toast.makeText(this@MainActivity, "출석 실패: $reason", Toast.LENGTH_LONG).show()
        }

        override fun onAttendanceAbsent(attendanceId: String?) {
            val pageView = contentFrame.getChildAt(0) ?: return
            updateStudentAttendanceUi(pageView, "결석", false)
            AlertDialog.Builder(this@MainActivity)
                .setTitle("결석 처리")
                .setMessage("UWB 재실 검증에 3회 연속 실패하여 결석 처리되었습니다.")
                .setPositiveButton("확인", null)
                .show()
        }
    }

    private fun readLoginInfo() {
        val pref = getSharedPreferences("LOGIN_INFO", MODE_PRIVATE)
        userId = pref.getString("userId", "") ?: ""
        userName = pref.getString("userName", "") ?: ""
        userRole = pref.getString("userRole", "student") ?: "student"
    }

    private fun loadPage(layoutResId: Int) {
        currentPageResId = layoutResId

        // 페이지가 바뀔 때마다 기존에 걸어둔 실시간 감지 리스너 해제 (메모리 누수 방지)
        removeRecordListener()
        removeSessionListener()
        contentFrame.removeAllViews()

        val pageView = LayoutInflater.from(this).inflate(layoutResId, contentFrame, false)
        contentFrame.addView(pageView)

        connectTopMenuButton(pageView)
        connectBottomMenu(pageView)
        loadJsonDataForPage(layoutResId, pageView)
    }

    private fun loadJsonDataForPage(layoutResId: Int, pageView: View) {
        when (layoutResId) {
            R.layout.main1 -> {
                loadCurrentClass(pageView)
            }
            R.layout.main_p_1 -> {
                loadProfessorPage(pageView)
                pageView.findViewById<View?>(R.id.btnProfessorAttendanceCheck)?.setOnClickListener {
                    startAttendanceSession(pageView)
                }
                pageView.findViewById<View?>(R.id.btnRollCallAttendance)?.setOnClickListener {
                    Toast.makeText(this, "호명출석 기능은 출석체크 시작 전만 사용할 수 있습니다", Toast.LENGTH_SHORT).show()
                }
            }
            R.layout.schedule_1 -> loadSchedule(pageView)
            R.layout.mypage -> {
                loadMyPage(pageView)
                loadSchedule(pageView)
            }
            R.layout.week_1, R.layout.week_2 -> loadAttendanceCalendar(pageView)
            R.layout.all_attendance -> loadAttendanceSummary(pageView)
        }
    }

    private fun connectTopMenuButton(pageView: View) {
        pageView.findViewById<View?>(R.id.btnMenu)?.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.END)
        }
    }

    private fun connectBottomMenu(pageView: View) {
        val btnHome = pageView.findViewById<View?>(R.id.btnBottomHome)
        val btnRefresh = pageView.findViewById<View?>(R.id.btnBottomRefresh)
        val btnNotice = pageView.findViewById<View?>(R.id.btnBottomNotice)
        val btnSchedule = pageView.findViewById<View?>(R.id.btnBottomSchedule)
        val btnLogout = pageView.findViewById<View?>(R.id.btnBottomLogout)

        btnHome?.setOnClickListener { if (userRole == "professor") loadPage(R.layout.main_p_1) else loadPage(R.layout.main1) }
        btnRefresh?.setOnClickListener {
            loadPage(currentPageResId)
            Toast.makeText(this, "새로고침되었습니다", Toast.LENGTH_SHORT).show()
        }
        btnNotice?.setOnClickListener { if (userRole == "professor") loadPage(R.layout.notice_2) else loadPage(R.layout.notice_1) }
        btnSchedule?.setOnClickListener { loadPage(R.layout.schedule_1) }
        btnLogout?.setOnClickListener { logout() }
    }

    private fun setupDrawerMenuClick() {
        findViewById<View?>(R.id.menuMyPage)?.setOnClickListener { moveTo(R.layout.mypage) }
        findViewById<View?>(R.id.menuSchedule)?.setOnClickListener { moveTo(R.layout.schedule_1) }
        findViewById<View?>(R.id.menuWeekAttendance)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            startActivity(Intent(this, WeekActivity::class.java))
        }
        findViewById<View?>(R.id.menuAllAttendance)?.setOnClickListener { moveTo(R.layout.all_attendance) }
        findViewById<View?>(R.id.menuConfirmPeriod)?.setOnClickListener { moveTo(R.layout.confirm_1) }
        findViewById<View?>(R.id.menuConfirmOfficial)?.setOnClickListener { moveTo(R.layout.confirm_2) }
        findViewById<View?>(R.id.menuNotice)?.setOnClickListener {
            if (userRole == "professor") moveTo(R.layout.notice_2) else moveTo(R.layout.notice_1)
        }
        findViewById<View?>(R.id.menuCancel)?.setOnClickListener {
            if (userRole == "professor") moveTo(R.layout.cancel_2) else moveTo(R.layout.cancel_1)
        }
    }

    private fun moveTo(layoutResId: Int) {
        drawerLayout.closeDrawer(GravityCompat.END)
        loadPage(layoutResId)
    }

    private fun loadCurrentClass(pageView: View) {
        val calendar = Calendar.getInstance(Locale.KOREA)
        val currentDayInt = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6; Calendar.SUNDAY -> 7; else -> 1
        }
        val nowStr = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date())

        fun timeToMinutes(timeStr: String): Int {
            val parts = timeStr.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return h * 60 + m
        }

        // 위쪽 UI 초기화
        pageView.findViewById<TextView>(R.id.tvDate)?.text = todayText()
        pageView.findViewById<TextView>(R.id.tvClassName)?.text = "시간표 분석 중..."
        pageView.findViewById<TextView>(R.id.tvClassTime)?.text = "-"
        pageView.findViewById<TextView>(R.id.tvPeriod)?.text = "-"

        // 아래쪽 UI 초기화
        pageView.findViewById<TextView>(R.id.tvDetailClassName)?.text = "분석 중..."
        pageView.findViewById<TextView>(R.id.tvDetailProfessor)?.text = "-"
        pageView.findViewById<TextView>(R.id.tvDetailTime)?.text = "-"
        pageView.findViewById<TextView>(R.id.tvDetailRoom)?.text = "-"

        FirebaseClient.get("Enrollment/$userId") { enrollmentJson ->
            val subjectCodes = mutableListOf<String>()
            val keys = enrollmentJson?.keys()
            if (keys != null) {
                while (keys.hasNext()) subjectCodes.add(keys.next())
            }

            if (subjectCodes.isEmpty()) {
                runOnUiThread {
                    pageView.findViewById<TextView>(R.id.tvClassName)?.text = "수강 신청 내역 없음"
                    updateStudentAttendanceUi(pageView, "미출석", false)
                }
                return@get
            }

            class ClassInstance(
                val subjectCode: String, val subjectName: String, val profName: String,
                val location: String, val fullScheduleStr: String,
                val dayOfWeekInt: Int, val startTime: String, val endTime: String
            )

            val allClasses = mutableListOf<ClassInstance>()
            var fetchCount = 0
            val lock = Any()

            subjectCodes.forEach { subjectCode ->
                FirebaseClient.get("Subjects/$subjectCode") { subjectJson ->
                    synchronized(lock) {
                        fetchCount++
                        if (subjectJson != null) {
                            val subCode = subjectJson.optString("subjectCode", subjectCode)
                            val subName = subjectJson.optString("subjectName", "알 수 없는 과목")
                            val profName = subjectJson.optString("professorName", "미정")
                            val scheduleObj = subjectJson.optJSONObject("schedule")

                            if (scheduleObj != null) {
                                val dayKeys = scheduleObj.keys()
                                val allSchedulesForThisSubject = mutableListOf<String>()
                                var representLocation = "미정"

                                // 첫 번째 루프: 이 과목의 전체 시간표 문자열 만들기 (예: 월 10:00-11:50, 수 10:00-11:50)
                                val dayKeysList = scheduleObj.keys().asSequence().toList()
                                for (dayKey in dayKeysList) {
                                    val dayObj = scheduleObj.optJSONObject(dayKey) ?: continue
                                    val dayKr = when (dayObj.optString("dayOfWeek", "").uppercase()) {
                                        "MONDAY"->"월"; "TUESDAY"->"화"; "WEDNESDAY"->"수"
                                        "THURSDAY"->"목"; "FRIDAY"->"금"; "SATURDAY"->"토"; "SUNDAY"->"일"
                                        else -> dayObj.optString("dayOfWeek", "")
                                    }
                                    if (representLocation == "미정") representLocation = dayObj.optString("location", "미정")

                                    val periodsArr = dayObj.optJSONArray("periods")
                                    var sTime = ""; var eTime = ""
                                    if (periodsArr != null) {
                                        for (i in 0 until periodsArr.length()) {
                                            val p = periodsArr.optJSONObject(i) ?: continue
                                            if (sTime.isEmpty()) sTime = p.optString("startTime", "")
                                            eTime = p.optString("endTime", "")
                                        }
                                    }
                                    if (sTime.isNotEmpty() && eTime.isNotEmpty()) {
                                        allSchedulesForThisSubject.add("$dayKr $sTime-$eTime")
                                    }
                                }
                                val fullScheduleStr = allSchedulesForThisSubject.joinToString(", ")

                                // 두 번째 루프: 가장 임박한 시간을 찾기 위해 요일별로 쪼개서 리스트에 담기
                                for (dayKey in dayKeysList) {
                                    val dayObj = scheduleObj.optJSONObject(dayKey) ?: continue
                                    val dayOfWeekStr = dayObj.optString("dayOfWeek", "")
                                    val dayInt = when (dayOfWeekStr.uppercase()) {
                                        "MONDAY", "월" -> 1; "TUESDAY", "화" -> 2; "WEDNESDAY", "수" -> 3
                                        "THURSDAY", "목" -> 4; "FRIDAY", "금" -> 5; "SATURDAY", "토" -> 6; "SUNDAY", "일" -> 7; else -> -1
                                    }

                                    if (dayInt != -1) {
                                        val periodsArr = dayObj.optJSONArray("periods")
                                        if (periodsArr != null) {
                                            var firstStart: String? = null
                                            var lastEnd: String? = null
                                            for (i in 0 until periodsArr.length()) {
                                                val p = periodsArr.optJSONObject(i) ?: continue
                                                val st = p.optString("startTime", "")
                                                val ed = p.optString("endTime", "")
                                                if (st.isNotEmpty() && ed.isNotEmpty()) {
                                                    if (firstStart == null) firstStart = st
                                                    lastEnd = ed
                                                }
                                            }
                                            if (firstStart != null && lastEnd != null) {
                                                allClasses.add(ClassInstance(subCode, subName, profName, representLocation, fullScheduleStr, dayInt, firstStart, lastEnd))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (fetchCount == subjectCodes.size) {
                            runOnUiThread {
                                if (allClasses.isEmpty()) {
                                    pageView.findViewById<TextView>(R.id.tvClassName)?.text = "등록된 수업 없음"
                                    pageView.findViewById<TextView>(R.id.tvDetailClassName)?.text = "등록된 수업 없음"
                                    return@runOnUiThread
                                }

                                val ongoingClass = allClasses.firstOrNull {
                                    it.dayOfWeekInt == currentDayInt && nowStr >= it.startTime && nowStr <= it.endTime
                                }

                                val currentTotalMinutes = currentDayInt * 24 * 60 + timeToMinutes(nowStr)
                                val upcomingClass = allClasses.minByOrNull {
                                    var classMins = it.dayOfWeekInt * 24 * 60 + timeToMinutes(it.startTime)
                                    if (classMins < currentTotalMinutes) classMins += 7 * 24 * 60
                                    classMins
                                }

                                val targetClass = ongoingClass ?: upcomingClass
                                if (targetClass == null) return@runOnUiThread

                                currentSubjectCode = targetClass.subjectCode
                                currentClassName = targetClass.subjectName
                                currentClassStartTime = targetClass.startTime
                                currentClassTime = "${targetClass.startTime} ~ ${targetClass.endTime}"

                                val startHour = currentClassStartTime.substringBefore(":").toIntOrNull() ?: 10
                                val periodNumber = when (startHour) {
                                    9->"1교시"; 10->"2교시"; 11->"3교시"; 12->"4교시"; 13->"5교시"; 14->"6교시"; 15->"7교시"; 16->"8교시"; else->"1교시"
                                }

                                val dayKr = when (targetClass.dayOfWeekInt) { 1->"월"; 2->"화"; 3->"수"; 4->"목"; 5->"금"; 6->"토"; 7->"일"; else->"" }
                                val periodText = if (ongoingClass != null) "수업 중" else "($dayKr) $periodNumber 예정"

                                pageView.findViewById<TextView>(R.id.tvClassName)?.text = currentClassName
                                pageView.findViewById<TextView>(R.id.tvClassTime)?.text = currentClassTime
                                pageView.findViewById<TextView>(R.id.tvPeriod)?.text = periodText

                                pageView.findViewById<TextView>(R.id.tvDetailClassName)?.text = currentClassName
                                pageView.findViewById<TextView>(R.id.tvDetailProfessor)?.text = targetClass.profName
                                pageView.findViewById<TextView>(R.id.tvDetailTime)?.text = targetClass.fullScheduleStr
                                pageView.findViewById<TextView>(R.id.tvDetailRoom)?.text = targetClass.location

                                updateStudentAttendanceUi(pageView, "미출석", false)
                                setupStudentAttendanceButton(pageView)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setupStudentAttendanceButton(pageView: View) {
        val btnAttendance = pageView.findViewById<Button?>(R.id.btnAttendance) ?: return
        setAttendanceButtonInactive(btnAttendance)

        val today = apiDateText()
        if (currentSubjectCode.isBlank()) currentSubjectCode = DEFAULT_SUBJECT_CODE

        val database = FirebaseDatabase.getInstance().reference
        activeRecordRef = database.child("Attendance_Records").child(currentSubjectCode).child(today).child(userId)

        attendanceRecordListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (currentPageResId != R.layout.main1) return

                val currentStatus = snapshot.child("finalStatus").getValue(String::class.java) ?: ""

                when (currentStatus) {
                    "출석", "출석 완료", "異쒖꽍" -> {
                        setAttendanceButtonCompleted(btnAttendance)
                        updateStudentAttendanceUi(pageView, "출석 완료", true)
                        btnAttendance.setOnClickListener { Toast.makeText(this@MainActivity, "이미 출석 처리되었습니다", Toast.LENGTH_SHORT).show() }
                        removeSessionListener() // 출석 완료 시 더 이상 세션 관찰 안함
                    }
                    "결석", "寃곗꽍" -> {
                        setAttendanceButtonInactive(btnAttendance)
                        updateStudentAttendanceUi(pageView, "결석", false)
                        btnAttendance.setOnClickListener { Toast.makeText(this@MainActivity, "결석 처리되었습니다", Toast.LENGTH_SHORT).show() }
                        removeSessionListener()
                    }
                    "지각" -> {
                        setAttendanceButtonInactive(btnAttendance)
                        updateStudentAttendanceUi(pageView, "지각", false)
                        btnAttendance.setOnClickListener { Toast.makeText(this@MainActivity, "지각 처리되었습니다", Toast.LENGTH_SHORT).show() }
                        removeSessionListener()
                    }
                    else -> {
                        updateStudentAttendanceUi(pageView, "미출석", false)
                        observeSessionStatus(pageView, btnAttendance, today)
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        activeRecordRef?.addValueEventListener(attendanceRecordListener!!)
    }

    private fun observeSessionStatus(pageView: View, btnAttendance: Button, today: String) {
        if (activeSessionRef != null) return // 이미 리스너가 작동 중이면 패스

        val database = FirebaseDatabase.getInstance().reference
        activeSessionRef = database.child("Attendance_Session").child(currentSubjectCode).child(today)

        attendanceSessionListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (currentPageResId != R.layout.main1) return

                if (!snapshot.exists()) {
                    setAttendanceButtonInactive(btnAttendance)
                    btnAttendance.setOnClickListener { Toast.makeText(this@MainActivity, "출석체크 시간이 아닙니다", Toast.LENGTH_SHORT).show() }
                    return
                }

                val status = snapshot.child("status").getValue(String::class.java) ?: "READY"
                val bluetoothEndAt = snapshot.child("bluetoothEndAt").getValue(Long::class.java) ?: 0L
                val pinEndAt = snapshot.child("pinEndAt").getValue(Long::class.java) ?: 0L
                val classStartAt = snapshot.child("classStartAt").getValue(Long::class.java) ?: 0L
                val now = System.currentTimeMillis()

                if (status == "BLUETOOTH_ACTIVE" && now <= bluetoothEndAt) {
                    setAttendanceButtonActive(btnAttendance)
                    btnAttendance.setOnClickListener { startBluetoothAttendanceScan() }
                } else if (now in (bluetoothEndAt + 1)..pinEndAt) {
                    setAttendanceButtonInactive(btnAttendance)

                    val sessionJson = JSONObject().apply {
                        put("status", status)
                        put("classStartAt", classStartAt)
                        put("pinEndAt", pinEndAt)
                    }
                    btnAttendance.setOnClickListener { checkStudentPinEligibilityAndShow(pageView, sessionJson) }
                } else {
                    setAttendanceButtonInactive(btnAttendance)
                    btnAttendance.setOnClickListener { Toast.makeText(this@MainActivity, "출석체크 시간이 아닙니다", Toast.LENGTH_SHORT).show() }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        activeSessionRef?.addValueEventListener(attendanceSessionListener!!)
    }

    private fun removeRecordListener() {
        attendanceRecordListener?.let { activeRecordRef?.removeEventListener(it) }
        attendanceRecordListener = null
        activeRecordRef = null
    }

    private fun removeSessionListener() {
        attendanceSessionListener?.let { activeSessionRef?.removeEventListener(it) }
        attendanceSessionListener = null
        activeSessionRef = null
    }

    private fun setAttendanceButtonActive(button: Button) {
        button.setBackgroundResource(R.drawable.bg_attendance_button_blue)
        button.text = "출석\n체크"
        button.isEnabled = true
        button.alpha = 1.0f
    }

    private fun setAttendanceButtonInactive(button: Button) {
        button.setBackgroundResource(R.drawable.bg_attendance_button_gray)
        button.text = "출석\n체크"
        button.isEnabled = true
        button.alpha = 1.0f
    }

    private fun setAttendanceButtonCompleted(button: Button) {
        button.setBackgroundResource(R.drawable.bg_attendance_button_gray)
        button.text = "출석\n완료"
        button.isEnabled = true
        button.alpha = 1.0f
    }

    private fun startBluetoothAttendanceScan() {
        if (userId.isBlank()) {
            Toast.makeText(this, "로그인 정보가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "BLE 출석 신호를 찾는 중...", Toast.LENGTH_SHORT).show()
        launcher.startStudent(userId)
    }

    private fun checkStudentPinEligibilityAndShow(pageView: View, sessionJson: JSONObject) {
        if (pinPopupShowing) return
        val today = apiDateText()

        FirebaseClient.get("Attendance_Records/$currentSubjectCode/$today/$userId") { recordJson ->
            val currentStatus = recordJson?.optString("finalStatus", "결석") ?: "결석"

            if (currentStatus == "출석" || currentStatus == "출석 완료") {
                runOnUiThread {
                    Toast.makeText(this, "이미 출석 처리되었습니다", Toast.LENGTH_SHORT).show()
                }
                return@get
            }

            runOnUiThread {
                showPinDialog(pageView, sessionJson)
            }
        }
    }

    private fun showPinDialog(pageView: View, sessionJson: JSONObject) {
        pinPopupShowing = true

        val dialogView = LayoutInflater.from(this).inflate(R.layout.pin, null)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val etPin1 = dialogView.findViewById<EditText>(R.id.etPin1)
        val etPin2 = dialogView.findViewById<EditText>(R.id.etPin2)
        val etPin3 = dialogView.findViewById<EditText>(R.id.etPin3)
        val etPin4 = dialogView.findViewById<EditText>(R.id.etPin4)

        val tvPinClassName = dialogView.findViewById<TextView>(R.id.tvPinClassName)
        val tvPinClassTime = dialogView.findViewById<TextView>(R.id.tvPinClassTime)
        val tvPinRemainTime = dialogView.findViewById<TextView>(R.id.tvPinRemainTime)
        val tvPinStatusGuide = dialogView.findViewById<TextView>(R.id.tvPinStatusGuide)
        val tvPinResultMessage = dialogView.findViewById<TextView>(R.id.tvPinResultMessage)

        val btnPinCancel = dialogView.findViewById<Button>(R.id.btnPinCancel)
        val btnPinConfirm = dialogView.findViewById<Button>(R.id.btnPinConfirm)

        val now = System.currentTimeMillis()
        val classStartAt = sessionJson.optLong("classStartAt", todayMillisFromTime(currentClassStartTime))
        val pinEndAt = sessionJson.optLong("pinEndAt", classStartAt + FIFTEEN_MINUTES)

        val remainMs = (pinEndAt - now).coerceAtLeast(0L)
        val remainMinute = remainMs / 1000 / 60
        val remainSecond = remainMs / 1000 % 60

        tvPinClassName.text = currentClassName
        tvPinClassTime.text = currentClassTime
        tvPinRemainTime.text = "PIN 입력 가능 시간 %02d:%02d".format(remainMinute, remainSecond)

        if (now < classStartAt + TEN_MINUTES) {
            tvPinStatusGuide.text = "현재 PIN 인증 시 출석 처리됩니다."
        } else {
            tvPinStatusGuide.text = "현재 PIN 인증 시 결석 처리됩니다."
        }

        btnPinCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnPinConfirm.setOnClickListener {
            val inputPin = etPin1.text.toString() +
                    etPin2.text.toString() +
                    etPin3.text.toString() +
                    etPin4.text.toString()

            if (System.currentTimeMillis() > pinEndAt) {
                tvPinResultMessage.visibility = View.VISIBLE
                tvPinResultMessage.text = "PIN 입력 시간이 종료되었습니다."
                return@setOnClickListener
            }

            if (inputPin.length != 4) {
                tvPinResultMessage.visibility = View.VISIBLE
                tvPinResultMessage.text = "PIN 4자리를 모두 입력해주세요."
                return@setOnClickListener
            }

            launcher.submitPin(userId, inputPin)
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            pinPopupShowing = false
        }

        dialog.show()
    }

    private fun startAttendanceSession(pageView: View) {
        if (currentSubjectCode.isBlank()) {
            currentSubjectCode = DEFAULT_SUBJECT_CODE
        }
        val now = System.currentTimeMillis()
        val classStartAt = todayMillisFromTime(currentClassStartTime)

        val pinEndAt = now + FIFTEEN_MINUTES

        launcher.startProfessor(currentSubjectCode, userId, classStartAt)

        val delayToAfter15 = (pinEndAt - now).coerceAtLeast(0L)
        phaseTransitionRunnable?.let { handler.removeCallbacks(it) }
        phaseTransitionRunnable = Runnable { transitionToAfter15Phase(pageView) }
        handler.postDelayed(phaseTransitionRunnable!!, delayToAfter15)
    }

    private fun transitionToAfter15Phase(pageView: View) {
        findChildByIdName<View>(pageView, "cardProfessorControlBefore15")?.visibility = View.GONE
        findChildByIdName<View>(pageView, "cardProfessorControlAfter15")?.visibility = View.VISIBLE
        findChildByIdName<View>(pageView, "cardUwbMiddleCheck")?.visibility = View.VISIBLE
        val btnRollCall = findChildByIdName<View>(pageView, "btnRollCallAttendance")
        val btnProfessorAttendanceCheck = findChildByIdName<View>(pageView, "btnProfessorAttendanceCheck")
        btnRollCall?.isEnabled = false
        btnRollCall?.alpha = 0.4f
        btnProfessorAttendanceCheck?.isEnabled = false
        btnProfessorAttendanceCheck?.alpha = 0.4f
    }

    private fun loadProfessorPage(pageView: View) {
        FirebaseClient.get("Subjects") { subjectsJson ->
            val firstSubjectCode = subjectsJson?.keys()?.asSequence()?.firstOrNull() ?: DEFAULT_SUBJECT_CODE
            currentSubjectCode = firstSubjectCode

            FirebaseClient.get("Subjects/$firstSubjectCode") { subjectJson ->
                val subject = FirebaseParsers.subject(subjectJson, firstSubjectCode)

                currentClassName = subject?.subjectName ?: "모바일 프로그래밍"

                val firstSchedule = subject?.schedules?.firstOrNull()
                val firstPeriod = firstSchedule?.periods?.firstOrNull()
                val lastPeriod = firstSchedule?.periods?.lastOrNull()

                currentClassStartTime = firstPeriod?.startTime ?: "10:00"
                currentClassTime = "${firstPeriod?.startTime ?: "10:00"} ~ ${lastPeriod?.endTime ?: "10:50"}"

                runOnUiThread {
                    setText(pageView, "tvDate", todayText())
                    setText(pageView, "tvPeriod", "1교시")
                    setText(pageView, "tvClassName", currentClassName)
                    setText(pageView, "tvClassTime", subject?.schedules?.joinToString(" / ") {
                        "${FirebaseParsers.convertDayToKorean(it.dayOfWeek)} ${it.periods.firstOrNull()?.startTime ?: ""}-${it.periods.lastOrNull()?.endTime ?: ""}"
                    } ?: currentClassTime)

                    setText(pageView, "tvAfter15ClassName", currentClassName)
                }
            }

            val today = apiDateText()

            FirebaseClient.get("Attendance_Session/$firstSubjectCode/$today") { sessionJson ->
                runOnUiThread {
                    updateProfessorSessionUi(pageView, sessionJson)
                }
            }

            FirebaseClient.get("Attendance_Records/$firstSubjectCode") { recordsJson ->
                runOnUiThread {
                    loadProfessorRows(pageView, recordsJson)
                }
            }
        }
    }

    private fun updateProfessorSessionUi(pageView: View, sessionJson: JSONObject?) {
        val cardBefore15 = findChildByIdName<View>(pageView, "cardProfessorControlBefore15")
        val cardAfter15 = findChildByIdName<View>(pageView, "cardProfessorControlAfter15")
        val cardUwb = findChildByIdName<View>(pageView, "cardUwbMiddleCheck")
        val btnRollCall = findChildByIdName<View>(pageView, "btnRollCallAttendance")
        val btnProfessorAttendanceCheck = findChildByIdName<View>(pageView, "btnProfessorAttendanceCheck")

        if (sessionJson == null) {
            cardBefore15?.visibility = View.VISIBLE
            cardAfter15?.visibility = View.GONE
            cardUwb?.visibility = View.GONE

            showPin(pageView, "")
            btnRollCall?.isEnabled = true
            btnRollCall?.alpha = 1.0f
            btnRollCall?.setBackgroundResource(R.drawable.bg_attendance_button_blue)
            btnProfessorAttendanceCheck?.isEnabled = true
            btnProfessorAttendanceCheck?.alpha = 1.0f
            btnProfessorAttendanceCheck?.setBackgroundResource(R.drawable.bg_attendance_button_blue)
            return
        }

        val now = System.currentTimeMillis()
        val pinEndAt = sessionJson.optLong("pinEndAt", todayMillisFromTime(currentClassStartTime) + FIFTEEN_MINUTES)
        val status = sessionJson.optString("status", "READY")
        val pinCode = sessionJson.optString("pinCode", "")
        val uwbCheckCount = sessionJson.optInt("uwbCheckCount", 0)
        setText(pageView, "tvUwbCheckCount", "${uwbCheckCount}회")

        if (now >= pinEndAt || status == "UWB_ACTIVE") {
            cardBefore15?.visibility = View.GONE
            cardAfter15?.visibility = View.VISIBLE
            cardUwb?.visibility = View.VISIBLE
            btnRollCall?.isEnabled = false
            btnRollCall?.alpha = 0.4f
            btnRollCall?.setBackgroundResource(R.drawable.bg_attendance_button_gray)
            btnProfessorAttendanceCheck?.isEnabled = false
            btnProfessorAttendanceCheck?.alpha = 0.4f
            btnProfessorAttendanceCheck?.setBackgroundResource(R.drawable.bg_attendance_button_gray)
        } else {
            cardBefore15?.visibility = View.VISIBLE
            cardAfter15?.visibility = View.GONE
            cardUwb?.visibility = View.GONE

            showPin(pageView, pinCode)

            btnRollCall?.isEnabled = false
            btnRollCall?.alpha = 0.4f
            btnRollCall?.setBackgroundResource(R.drawable.bg_attendance_button_gray)

            btnProfessorAttendanceCheck?.isEnabled = false
            btnProfessorAttendanceCheck?.alpha = 0.6f
            btnProfessorAttendanceCheck?.setBackgroundResource(R.drawable.bg_attendance_button_gray)
        }
    }

    private fun loadProfessorRows(pageView: View, recordsJson: JSONObject?) {
        val rows = findChildByIdName<LinearLayout>(pageView, "layoutStudentAttendanceRows")
        rows?.removeAllViews()

        FirebaseClient.get("Users") { usersJson ->
            val keys = usersJson?.keys()
            var total = 0
            var present = 0
            var late = 0
            var absent = 0

            val studentList = mutableListOf<Triple<String, String, String>>()

            if (keys != null) {
                while (keys.hasNext()) {
                    val key = keys.next()
                    val user = FirebaseParsers.user(usersJson.optJSONObject(key), key) ?: continue
                    if (user.userType != "STUDENT") continue

                    val status = findLatestAttendanceStatus(recordsJson, user.userId)

                    total++

                    when (status) {
                        "출석", "출석 완료" -> present++
                        "지각" -> late++
                        "결석", "미출석" -> absent++
                    }

                    studentList.add(Triple(user.userId, user.name, status))
                }
            }

            if (total == 0) total = 1

            val finalTotal = total
            val finalPresent = present
            val finalLate = late
            val finalAbsent = absent

            runOnUiThread {
                studentList.forEach { (studentId, name, status) ->
                    addStudentRow(pageView, studentId, name, status)
                }
                setText(pageView, "tvStudentCount", "총 ${finalTotal}명")
                setText(pageView, "tvAttendanceRate", "${finalPresent * 100 / finalTotal}%")
                setText(pageView, "tvLateRate", "${finalLate * 100 / finalTotal}%")
                setText(pageView, "tvAbsentRate", "${finalAbsent * 100 / finalTotal}%")
            }
        }
    }

    private fun findLatestAttendanceStatus(recordsJson: JSONObject?, targetUserId: String): String {
        if (recordsJson == null) return "미출석"
        val dateKeys = recordsJson.keys()
        var result = "미출석"
        while (dateKeys.hasNext()) {
            val dateKey = dateKeys.next()
            val dateObject = recordsJson.optJSONObject(dateKey) ?: continue
            val userObject = dateObject.optJSONObject(targetUserId) ?: continue
            result = userObject.optString("finalStatus", "미출석")
        }
        return result
    }

    private fun addStudentRow(pageView: View, studentId: String, name: String, status: String) {
        val parent = findChildByIdName<LinearLayout>(pageView, "layoutStudentAttendanceRows") ?: return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(0), dpToPx(8), dpToPx(0), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(38))
        }
        row.addView(makeRowText(studentId, 1.45f))
        row.addView(makeRowText(name, 1.0f))
        row.addView(makeStatusIcon(status == "출석" || status == "출석 완료", R.drawable.attendanceweek, 0.75f))
        row.addView(makeStatusIcon(status == "결석" || status == "미출석", R.drawable.absentweek, 0.75f))
        row.addView(makeStatusIcon(status == "지각", R.drawable.lateweek, 0.75f))
        parent.addView(row)
    }

    private fun makeRowText(value: String, weight: Float): TextView {
        return TextView(this).apply {
            text = value
            textSize = 12f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.parseColor("#222222"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
        }
    }

    private fun makeStatusIcon(isVisible: Boolean, drawableResId: Int, weight: Float): FrameLayout {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
            val icon = ImageView(this@MainActivity).apply {
                setImageResource(drawableResId)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
                layoutParams = FrameLayout.LayoutParams(dpToPx(18), dpToPx(18), Gravity.CENTER)
            }
            addView(icon)
        }
    }

    private fun showPin(pageView: View, pinCode: String) {
        val pin = pinCode.padEnd(4, ' ')
        setText(pageView, "tvPinDigit1", pin[0].toString())
        setText(pageView, "tvPinDigit2", pin[1].toString())
        setText(pageView, "tvPinDigit3", pin[2].toString())
        setText(pageView, "tvPinDigit4", pin[3].toString())
    }

    private fun loadSchedule(pageView: View) {
        val etSubjectCodeInput = pageView.findViewById<EditText>(R.id.etSubjectCodeInput)
        val btnAddSubject = pageView.findViewById<TextView>(R.id.btnAddSubject)

        btnAddSubject?.setOnClickListener {
            val inputCode = etSubjectCodeInput?.text.toString().trim()

            if (inputCode.isEmpty()) {
                Toast.makeText(this@MainActivity, "과목코드를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val database = FirebaseDatabase.getInstance().reference
            database.child("Subjects").child(inputCode).get().addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    database.child("Enrollment").child(userId).child(inputCode).setValue(true)
                        .addOnSuccessListener {
                            Toast.makeText(this@MainActivity, "과목($inputCode)이 성공적으로 추가되었습니다!", Toast.LENGTH_SHORT).show()
                            etSubjectCodeInput?.text?.clear()
                            loadSchedule(pageView)
                        }
                        .addOnFailureListener {
                            Toast.makeText(this@MainActivity, "과목 추가에 실패했습니다. 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
                        }
                } else {
                    Toast.makeText(this@MainActivity, "존재하지 않는 과목코드입니다.", Toast.LENGTH_SHORT).show()
                }
            }.addOnFailureListener {
                Toast.makeText(this@MainActivity, "과목 확인 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        val parent = findChildByIdName<FrameLayout>(pageView, "classBlockLayer")
        runOnUiThread { parent?.removeAllViews() }

        FirebaseClient.get("Enrollment/$userId") { enrollmentJson ->
            val subjectCodes = mutableListOf<String>()
            val keys = enrollmentJson?.keys()
            if (keys != null) {
                while (keys.hasNext()) subjectCodes.add(keys.next())
            }

            if (subjectCodes.isEmpty()) {
                runOnUiThread {
                    setText(pageView, "tvCurrentClassName", "수강 신청 내역 없음")
                    findChildByIdName<View>(pageView, "currentClassEmptyCard")?.visibility = View.VISIBLE
                }
                return@get
            }

            val subjects = mutableListOf<com.example.myapplication.model.domain.model.Subject>()
            var fetchCount = 0
            val lock = Any()

            subjectCodes.forEach { subjectCode ->
                FirebaseClient.get("Subjects/$subjectCode") { subjectJson ->
                    synchronized(lock) {
                        fetchCount++
                        if (subjectJson != null) {
                            val subCode = subjectJson.optString("subjectCode", subjectCode)
                            val subName = subjectJson.optString("subjectName", "알 수 없음")
                            val profName = subjectJson.optString("professorName", "미정")
                            val scheduleObj = subjectJson.optJSONObject("schedule")

                            val scheduleMap = mutableMapOf<String, com.example.myapplication.model.domain.model.DaySchedule>()

                            if (scheduleObj != null) {
                                val dayKeys = scheduleObj.keys()
                                while (dayKeys.hasNext()) {
                                    val dayName = dayKeys.next()
                                    val dayObj = scheduleObj.optJSONObject(dayName) ?: continue
                                    val dayOfWeekStr = dayObj.optString("dayOfWeek", dayName)
                                    val locationStr = dayObj.optString("location", "미정")

                                    val periodsList = mutableListOf<com.example.myapplication.model.domain.model.Period>()
                                    val periodsArr = dayObj.optJSONArray("periods")
                                    if (periodsArr != null) {
                                        for (i in 0 until periodsArr.length()) {
                                            val p = periodsArr.optJSONObject(i) ?: continue
                                            val st = p.optString("startTime", "")
                                            val ed = p.optString("endTime", "")
                                            if (st.isNotEmpty() && ed.isNotEmpty()) {
                                                periodsList.add(com.example.myapplication.model.domain.model.Period(st, ed))
                                            }
                                        }
                                    }
                                    if (periodsList.isNotEmpty()) {
                                        scheduleMap[dayOfWeekStr] = com.example.myapplication.model.domain.model.DaySchedule(
                                            dayOfWeek = dayOfWeekStr, location = locationStr, periods = periodsList
                                        )
                                    }
                                }
                            }
                            subjects.add(
                                com.example.myapplication.model.domain.model.Subject(
                                    subjectCode = subCode, subjectName = subName, professorName = profName, schedule = scheduleMap
                                )
                            )
                        }
                        if (fetchCount == subjectCodes.size) {
                            runOnUiThread { renderScheduleSubjects(pageView, parent, subjects) }
                        }
                    }
                }
            }
        }
    }

    private fun loadStudentScheduleFromRest(pageView: View) {
        val etSubjectCodeInput = pageView.findViewById<EditText>(R.id.etSubjectCodeInput)
        val btnAddSubject = pageView.findViewById<TextView>(R.id.btnAddSubject)

        btnAddSubject?.setOnClickListener {
            val inputCode = etSubjectCodeInput?.text.toString().trim()

            if (inputCode.isEmpty()) {
                Toast.makeText(this@MainActivity, "과목코드를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val database: DatabaseReference = FirebaseDatabase.getInstance().reference

            database.child("Subjects").child(inputCode).get().addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    database.child("Enrollment").child(userId).child(inputCode).setValue(true)
                        .addOnSuccessListener {
                            Toast.makeText(this@MainActivity, "과목($inputCode)이 추가되었습니다!", Toast.LENGTH_SHORT).show()
                            etSubjectCodeInput?.text?.clear()
                            loadStudentScheduleFromRest(pageView)
                        }
                        .addOnFailureListener {
                            Toast.makeText(this@MainActivity, "과목 추가 실패. 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
                        }
                } else {
                    Toast.makeText(this@MainActivity, "존재하지 않는 과목코드입니다.", Toast.LENGTH_SHORT).show()
                }
            }.addOnFailureListener {
                Toast.makeText(this@MainActivity, "과목 확인 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        lifecycleScope.launch {
            val parent = findChildByIdName<FrameLayout>(pageView, "classBlockLayer")

            runOnUiThread {
                parent?.removeAllViews()
            }

            try {
                val subjects = repository.getEnrolledSubjects(userId)

                runOnUiThread {
                    renderScheduleSubjects(pageView, parent, subjects)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadProfessorScheduleFromRest(pageView: View) {
        lifecycleScope.launch {
            val parent = findChildByIdName<FrameLayout>(pageView, "classBlockLayer")
            parent?.removeAllViews()

            try {
                val subjects = repository.getSubjects()
                renderScheduleSubjects(pageView, parent, subjects)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun renderScheduleSubjects(pageView: View, parent: FrameLayout?, subjects: List<Subject>) {
        if (parent == null) return
        parent.post {
            parent.removeAllViews()
            if (subjects.isEmpty()) {
                setText(pageView, "tvCurrentClassName", "등록된 시간표 없음")
                findChildByIdName<View>(pageView, "currentClassEmptyCard")?.visibility = View.VISIBLE
                return@post
            }
            findChildByIdName<View>(pageView, "currentClassEmptyCard")?.visibility = View.GONE

            subjects.forEachIndexed { index, subject ->
                addSubjectBlock(parent, subject, index)
                if (index == 0) {
                    setText(pageView, "tvCurrentClassName", subject.subjectName)
                    setText(pageView, "tvDetailProfessor", subject.professorName)
                    setText(pageView, "tvDetailRoom", subject.schedule.values.firstOrNull()?.location ?: "미정")
                    setText(pageView, "tvDetailCourseCode", subject.subjectCode)
                    setText(pageView, "tvDetailTime", subject.schedule.values.joinToString(" / ") {
                        val dayKr = when(it.dayOfWeek.uppercase()) { "MONDAY"->"월"; "TUESDAY"->"화"; "WEDNESDAY"->"수"; "THURSDAY"->"목"; "FRIDAY"->"금"; else->it.dayOfWeek }
                        val validP = it.periods.filterNotNull()
                        "$dayKr ${validP.firstOrNull()?.startTime ?: ""}-${validP.lastOrNull()?.endTime ?: ""}"
                    })
                }
            }
        }
    }

    private fun loadMyPage(pageView: View) {
        FirebaseClient.get("Users/$userId") { userJson ->
            val user = FirebaseParsers.user(userJson, userId)
            if (userRole == "professor") {
                setText(pageView, "tvProfessorName", user?.name ?: userName)
                setText(pageView, "tvProfessorMajor", "소프트웨어학과")
            } else {
                setText(pageView, "tvStudentName", user?.name ?: userName)
                setText(pageView, "tvStudentMajor", "소프트웨어학과")
                setText(pageView, "tvStudentInfo", user?.userId ?: userId)
            }
        }
    }

    private fun loadAttendanceCalendar(pageView: View) {
        FirebaseClient.get("Attendance_Records") { recordsRoot ->
            val result = StringBuilder()
            val subjectKeys = recordsRoot?.keys()
            if (subjectKeys != null) {
                while (subjectKeys.hasNext()) {
                    val subjectCode = subjectKeys.next()
                    val subjectObject = recordsRoot.optJSONObject(subjectCode) ?: continue
                    val dateKeys = subjectObject.keys()
                    while (dateKeys.hasNext()) {
                        val date = dateKeys.next()
                        val userRecord = subjectObject.optJSONObject(date)?.optJSONObject(userId) ?: continue
                        result.append(date).append(" / ").append(subjectCode).append(" / ").append(userRecord.optString("finalStatus", "")).append("\n")
                    }
                }
            }
            runOnUiThread {
                setText(pageView, "tvAttendanceCalendar", result.toString())
                addSimpleText(pageView, "layoutAttendanceCalendar", result.toString())
            }
        }
    }

    private fun loadAttendanceSummary(pageView: View) {
        FirebaseClient.get("Attendance_Records") { recordsRoot ->
            var present = 0; var late = 0; var absent = 0
            val subjectKeys = recordsRoot?.keys()
            if (subjectKeys != null) {
                while (subjectKeys.hasNext()) {
                    val subjectCode = subjectKeys.next()
                    val subjectObject = recordsRoot.optJSONObject(subjectCode) ?: continue
                    val dateKeys = subjectObject.keys()
                    while (dateKeys.hasNext()) {
                        val date = dateKeys.next()
                        val userRecord = subjectObject.optJSONObject(date)?.optJSONObject(userId) ?: continue
                        when (userRecord.optString("finalStatus", "")) {
                            "출석", "출석 완료" -> present++
                            "지각" -> late++
                            "결석" -> absent++
                        }
                    }
                }
            }
            val total = (present + late + absent).coerceAtLeast(1)
            val text = "출석 ${present * 100 / total}% / 지각 ${late * 100 / total}% / 결석 ${absent * 100 / total}%"
            runOnUiThread {
                setText(pageView, "tvAttendanceSummary", text)
                addSimpleText(pageView, "layoutAttendanceSummary", text)
            }
        }
    }

    private fun addSubjectBlock(parent: FrameLayout, subject: Subject, index: Int) {
        val colors = listOf("#8FA2C7", "#B9AAA5", "#79B2B8", "#A7B58D", "#C39DA4")
        val color = colors[index % colors.size]
        val cleanName = subject.subjectName.replace(" (영어강의)", "").replace(" (실시간화상강의)", "")

        val usableWidth = parent.width - parent.paddingStart - parent.paddingEnd
        val columnWidth = if (usableWidth > 0) usableWidth / 5 else dpToPx(50)

        subject.schedule.values.forEach { daySchedule ->
            val location = daySchedule.location.ifEmpty { "미정" }
            val dayIndex = when (daySchedule.dayOfWeek.uppercase()) {
                "MONDAY", "월" -> 0; "TUESDAY", "화" -> 1; "WEDNESDAY", "수" -> 2;
                "THURSDAY", "목" -> 3; "FRIDAY", "금" -> 4; else -> return@forEach
            }

            daySchedule.periods.forEach { period ->
                if (period == null) return@forEach

                val block = TextView(this@MainActivity).apply {
                    text = "$cleanName\n$location"
                    setTextColor(Color.WHITE)
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))
                    setBackgroundColor(Color.parseColor(color))
                }

                val params = FrameLayout.LayoutParams(
                    columnWidth,
                    getBlockHeightByTime(period.startTime, period.endTime)
                ).apply {
                    leftMargin = dayIndex * columnWidth
                    topMargin = getTopMarginByTime(period.startTime)
                }
                parent.addView(block, params)
            }
        }
    }

    private fun getTopMarginByTime(startTimeStr: String): Int {
        val parts = startTimeStr.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 9
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val totalMinutesFromBase = (hour * 60 + minute) - (9 * 60)
        return dpToPx((totalMinutesFromBase * (52.0 / 60.0)).toInt())
    }

    private fun getBlockHeightByTime(startTimeStr: String, endTimeStr: String): Int {
        val startParts = startTimeStr.split(":")
        val startHour = startParts.getOrNull(0)?.toIntOrNull() ?: 9
        val startMin = startParts.getOrNull(1)?.toIntOrNull() ?: 0

        val endParts = endTimeStr.split(":")
        val endHour = endParts.getOrNull(0)?.toIntOrNull() ?: 10
        val endMin = endParts.getOrNull(1)?.toIntOrNull() ?: 0

        var durationMinutes = (endHour * 60 + endMin) - (startHour * 60 + startMin)

        if (durationMinutes % 60 == 50) {
            durationMinutes += 10
        } else if (durationMinutes % 60 == 45) {
            durationMinutes += 15
        }

        return dpToPx((durationMinutes * (52.0 / 60.0)).toInt()).coerceAtLeast(dpToPx(20))
    }

    private fun addSimpleText(pageView: View, parentIdName: String, value: String) {
        val parent = findChildByIdName<LinearLayout>(pageView, parentIdName) ?: return
        parent.removeAllViews()
        parent.addView(TextView(this).apply { text = value; textSize = 14f; setTextColor(Color.parseColor("#222222")); setPadding(16, 12, 16, 12) })
    }

    private fun todayMillisFromTime(time: String): Long {
        val parts = time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 10
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val calendar = Calendar.getInstance(Locale.KOREA)
        calendar.time = Date()
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun setText(pageView: View, idName: String, value: String) {
        findChildByIdName<TextView>(pageView, idName)?.text = value
    }

    private fun updateStudentAttendanceUi(pageView: View, statusText: String, isCompleted: Boolean) {
        val ivCheckIcon = pageView.findViewById<ImageView?>(R.id.ivCheckIcon)
        val tvAttendanceStatus = pageView.findViewById<TextView?>(R.id.tvAttendanceStatus)
        tvAttendanceStatus?.text = statusText
        if (isCompleted) {
            ivCheckIcon?.setImageResource(R.drawable.mainblue)
            tvAttendanceStatus?.setTextColor(Color.parseColor(BLUE_ACTIVE))
        } else {
            ivCheckIcon?.setImageResource(R.drawable.maingray)
            tvAttendanceStatus?.setTextColor(Color.parseColor(GRAY_INACTIVE))
        }
    }

    private inline fun <reified T> findChildByIdName(pageView: View, idName: String): T? {
        val id = resources.getIdentifier(idName, "id", packageName)
        return if (id != 0) pageView.findViewById(id) else null
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun todayText(): String = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date())

    private fun apiDateText(): String = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())

    private fun logout() {
        getSharedPreferences("LOGIN_INFO", MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("login_pref", MODE_PRIVATE).edit().clear().apply()
        Toast.makeText(this, "로그아웃되었습니다", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
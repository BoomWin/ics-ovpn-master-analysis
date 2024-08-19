/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.core;

import android.annotation.SuppressLint;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.blinkt.openvpn.R;

public class OpenVPNThread implements Runnable {
    private static final String DUMP_PATH_STRING = "Dump path: ";
    @SuppressLint("SdCardPath")
    private static final String TAG = "OpenVPN";
    // 1380308330.240114 18000002 Send to HTTP proxy: 'X-Online-Host: bla.blabla.com'
    private static final Pattern LOG_PATTERN = Pattern.compile("(\\d+).(\\d+) ([0-9a-f])+ (.*)");
    public static final int M_FATAL = (1 << 4);
    public static final int M_NONFATAL = (1 << 5);
    public static final int M_WARN = (1 << 6);
    public static final int M_DEBUG = (1 << 7);
    private final FutureTask<OutputStream> mStreamFuture;
    private OutputStream mOutputStream;

    private String[] mArgv;
    private Process mProcess;
    private String mNativeDir;
    private String mTmpDir;
    private OpenVPNService mService;
    private String mDumpPath;
    private boolean mNoProcessExitStatus = false;

    public OpenVPNThread(OpenVPNService service, String[] argv, String nativelibdir, String tmpdir) {
        mArgv = argv;
        mNativeDir = nativelibdir;
        mTmpDir = tmpdir;
        mService = service;
        mStreamFuture = new FutureTask<>(() -> mOutputStream);
    }


    public void stopProcess() {
        mProcess.destroy();
    }

    void setReplaceConnection()
    {
        mNoProcessExitStatus=true;
    }

    @Override
    public void run() {
        try {
            Log.i(TAG, "Starting openvpn");
            // OpenVPN 프로세스 시작 부분.
            // argv = /data/data/your.package.name/cache/c_minipievpn.arm64-v8a --config stdin 이러한 형태로 값이 전달 되는 거 아님?
            startOpenVPNThreadArgs(mArgv);
            Log.i(TAG, "OpenVPN process exited");
        } catch (Exception e) {
            VpnStatus.logException("Starting OpenVPN Thread", e);
            Log.e(TAG, "OpenVPNThread Got " + e.toString());
        } finally {
            int exitvalue = 0;
            try {
                if (mProcess != null)
                    // Process 객체와 연결된 프로세스가 종료될 때 까지 현재 스레드는 대기상태
                    // 프로세스가 종료될 때까지 다른 작업을 수행하지 않고 기다리게됨
                    // mProcess가 종료되면, wiatFor() 메서드는 해당프로세스의 종료코드를 반환
                    // 프로세스가 성공적으로 완료되면 0, 실패 다른 값.
                    exitvalue = mProcess.waitFor();
            } catch (IllegalThreadStateException ite) {
                VpnStatus.logError("Illegal Thread state: " + ite.getLocalizedMessage());
            } catch (InterruptedException ie) {
                VpnStatus.logError("InterruptedException: " + ie.getLocalizedMessage());
            }
            if (exitvalue != 0) {
                VpnStatus.logError("Process exited with exit value " + exitvalue);
            }

            if (!mNoProcessExitStatus)
                VpnStatus.updateStateString("NOPROCESS", "No process running.", R.string.state_noprocess, ConnectionStatus.LEVEL_NOTCONNECTED);

            if (mDumpPath != null) {
                try {
                    BufferedWriter logout = new BufferedWriter(new FileWriter(mDumpPath + ".log"));
                    SimpleDateFormat timeformat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMAN);
                    for (LogItem li : VpnStatus.getlogbuffer()) {
                        String time = timeformat.format(new Date(li.getLogtime()));
                        logout.write(time + " " + li.getString(mService) + "\n");
                    }
                    logout.close();
                    VpnStatus.logError(R.string.minidump_generated);
                } catch (IOException e) {
                    VpnStatus.logError("Writing minidump log: " + e.getLocalizedMessage());
                }
            }

            if (!mNoProcessExitStatus)
                mService.openvpnStopped();
            Log.i(TAG, "Exiting");
        }
    }

    private void startOpenVPNThreadArgs(String[] argv) {
        LinkedList<String> argvlist = new LinkedList<String>();

        // argv = /data/data/your.package.name/cache/c_minipievpn.arm64-v8a --config stdin
        // 배열의 모든 요소를 리스트에 추가

        Collections.addAll(argvlist, argv);

        //argvlist = /data/data/your.package.name/cache/c_minipievpn.arm64-v8a --config stdin
        // processBuilder 는 자바에서 새로운 프로세스를 생성하고, 해당 프로세스의 실행을 설정하는데 사용됨.
        // ProcessBuilder (List<String> command) : 실행할 명령과 그 인자들을 리스트 형태로 받음.
        ProcessBuilder pb = new ProcessBuilder(argvlist);
        // 위와 같은 객체를 생성하게 되면
        // 원래는
        // ProcessBuilder pb = new ProcessBuilder("echo", "hello");
        // 이런식으로 "echo"가 명령어이고, "Hello,World!"는 그 명령어에 대한 인수가 된다.

        // Hack O rama

        // 최종적으로 구성된 라이브러리 검색 경로를 반환받았음.
        String lbpath = genLibraryPath(argv, pb);


        pb.environment().put("LD_LIBRARY_PATH", lbpath);
        pb.environment().put("TMPDIR", mTmpDir);

        // 프로세스의 표준 에러 스트림을 표준 출력 스트림으로 리디렉션함.
        // 기본적으로 프로세스는 두 개의 별도 출력 스트림(stdout 과 stderr)을 갖음.
        // 이 설정을 true로 하면, 두 스트림이 하나로 병합됨.
        // 프로세스의 모든 출력을 단일 스트림에서 읽을 수 있게함.
        pb.redirectErrorStream(true);
        try {
            mProcess = pb.start();
            // Close the output, since we don't need it

            InputStream in = mProcess.getInputStream();
            OutputStream out = mProcess.getOutputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

            // 출력 스트림을 클래스 변수에 저장함.
            mOutputStream = out;
            // mStreaFuture 태스크를 실행함.
            // mStreamFuture.run()이 호출되면, FutureTask 는 내부적으로 결과(여기서는 mOutputStream)를 저장하고, 작업이 완료되었음을 표시함.
            mStreamFuture.run();

            // 무한 루프를 시작하여 지속적으로 OpenVPN 의 출력을 처리함.
            while (true) {
                // OpenVPN 프로세스의 출력에서 한 줄을 읽음.
                String logline = br.readLine();
                if (logline == null)
                    return;

                if (logline.startsWith(DUMP_PATH_STRING))
                    mDumpPath = logline.substring(DUMP_PATH_STRING.length());

                Matcher m = LOG_PATTERN.matcher(logline);
                if (m.matches()) {
                    int flags = Integer.parseInt(m.group(3), 16);
                    String msg = m.group(4);
                    int logLevel = flags & 0x0F;

                    VpnStatus.LogLevel logStatus = VpnStatus.LogLevel.INFO;

                    if ((flags & M_FATAL) != 0)
                        logStatus = VpnStatus.LogLevel.ERROR;
                    else if ((flags & M_NONFATAL) != 0)
                        logStatus = VpnStatus.LogLevel.WARNING;
                    else if ((flags & M_WARN) != 0)
                        logStatus = VpnStatus.LogLevel.WARNING;
                    else if ((flags & M_DEBUG) != 0)
                        logStatus = VpnStatus.LogLevel.VERBOSE;

                    if (msg.startsWith("MANAGEMENT: CMD"))
                        logLevel = Math.max(4, logLevel);

                    VpnStatus.logMessageOpenVPN(logStatus, logLevel, msg);
                    VpnStatus.addExtraHints(msg);
                } else {
                    VpnStatus.logInfo("P:" + logline);
                }

                if (Thread.interrupted()) {
                    throw new InterruptedException("OpenVpn process was killed form java code");
                }
            }
        } catch (InterruptedException | IOException e) {
            VpnStatus.logException("Error reading from output of OpenVPN process", e);
            mStreamFuture.cancel(true);
            stopProcess();
        }


    }

    private String genLibraryPath(String[] argv, ProcessBuilder pb) {
        // Hack until I find a good way to get the real library path
        // 그러면 여기서 기존에 있던 경로 cache가 lib으로 바뀌고 applibpath에 저장됨.
        String applibpath = argv[0].replaceFirst("/cache/.*$", "/lib");


        // 현재 설정된 라이브러리 경로를 가져옴.
        String lbpath = pb.environment().get("LD_LIBRARY_PATH");

        // 기존 경로가 없으며 applibpath를 사용하고, 있으면 applibpath를 앞에 추가함.
        if (lbpath == null)
            lbpath = applibpath;
        else
            lbpath = applibpath + ":" + lbpath;

        if (!applibpath.equals(mNativeDir)) {
            lbpath = mNativeDir + ":" + lbpath;
        }
        // 최종적으로 구성된 라이브러리 검색 경로를 반환함.
        return lbpath;
    }

    public OutputStream getOpenVPNStdin() throws ExecutionException, InterruptedException {
        return mStreamFuture.get();
    }
}

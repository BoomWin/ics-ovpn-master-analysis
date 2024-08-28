/*
 * Copyright (c) 2012-2017 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.remote;

import android.app.Activity;
import android.app.Fragment;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.List;

import de.blinkt.openvpn.api.APIVpnProfile;
import de.blinkt.openvpn.api.IOpenVPNAPIService;
import de.blinkt.openvpn.api.IOpenVPNStatusCallback;

public class MainFragment extends Fragment implements View.OnClickListener, Handler.Callback {

    private TextView mHelloWorld;
    private Button mStartVpn;
    private TextView mMyIp;
    private TextView mStatus;

    // c언어로 치면 함수라고 생각하면 됨
    // 프래그먼트의 UI를 생성하고 초기화하는 역할
    // 프래그먼트가 처음 생성될 때 호출된다.
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // inflater.inflate 메서드를 이용해서 fragment_main 레이아웃 xml 파일을 실제 뷰 객체로 변환하고 v에 저장
        View v = inflater.inflate(R.layout.fragment_main, container, false);
        v.findViewById(R.id.disconnect).setOnClickListener(this);
        v.findViewById(R.id.getMyIP).setOnClickListener(this);
        v.findViewById(R.id.startembedded).setOnClickListener(this);
        v.findViewById(R.id.addNewProfile).setOnClickListener(this);
        v.findViewById(R.id.addNewProfileEdit).setOnClickListener(this);
        mHelloWorld = (TextView) v.findViewById(R.id.helloworld);
        mStartVpn = (Button) v.findViewById(R.id.startVPN);
        mStatus = (TextView) v.findViewById(R.id.status);
        mMyIp = (TextView) v.findViewById(R.id.MyIpText);


        return v;

    }

    private static final int MSG_UPDATE_STATE = 0;
    private static final int MSG_UPDATE_MYIP = 1;
    private static final int START_PROFILE_EMBEDDED = 2;
    private static final int START_PROFILE_BYUUID = 3;
    private static final int ICS_OPENVPN_PERMISSION = 7;
    private static final int PROFILE_ADD_NEW = 8;
    private static final int PROFILE_ADD_NEW_EDIT = 9;

    // OpenVPN API를 사용하여 VPN 서비스를 제어하기 위해 사용됨.
    // 여기서 IOpenVPNAPIService가 인터페이스 역할을 한다고함.
    protected IOpenVPNAPIService mService=null;
    // Handler 라는 클래스가 있음. 핸들러는 ui 충돌 쓰레드에서 메인으로 전달해주기 위함.
    private Handler mHandler;




    private void startEmbeddedProfile(boolean addNew, boolean editable, boolean startAfterAdd)
    {
        try {
            InputStream conf;
            /* Try opening test.local.conf first */
            try {
                conf = getActivity().getAssets().open("test.local.conf");
            }
            catch (IOException e) {
                conf = getActivity().getAssets().open("test.conf");
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(conf));
            StringBuilder config = new StringBuilder();
            String line;
            while(true) {
                line = br.readLine();
                if(line == null)
                    break;
                config.append(line).append("\n");
            }
            br.close();
            conf.close();

            if (addNew) {
                String name = editable ? "Profile from remote App" : "Non editable profile";
                // 클래스를 변수 타입으로 가져가면 그 클래스 안의 객체를 참조할 수 있게된다.
                APIVpnProfile profile = mService.addNewVPNProfile(name, editable, config.toString());
                mService.startProfile(profile.mUUID);

            } else
                mService.startVPN(config.toString());
        } catch (IOException | RemoteException e) {
            e.printStackTrace();
        }
        Toast.makeText(getActivity(), "Profile started/added", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onStart() {
        super.onStart();
        mHandler = new Handler(this);
        bindService();
    }


    private IOpenVPNStatusCallback mCallback = new IOpenVPNStatusCallback.Stub() {
        /**
         * This is called by the remote service regularly to tell us about
         * new values.  Note that IPC calls are dispatched through a thread
         * pool running in each process, so the code executing here will
         * NOT be running in our main thread like most other things -- so,
         * to update the UI, we need to use a Handler to hop over there.
         */

        @Override
        public void newStatus(String uuid, String state, String message, String level)
                throws RemoteException {
            Message msg = Message.obtain(mHandler, MSG_UPDATE_STATE, state + "|" + message);
            msg.sendToTarget();

        }

    };


    /**
     * Class for interacting with the main interface of the service.
     */
    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // This is called when the connection with the service has been
            // established, giving us the service object we can use to
            // interact with the service.  We are communicating with our
            // service through an IDL interface, so get a client-side
            // representation of that from the raw service object.

            mService = IOpenVPNAPIService.Stub.asInterface(service);

            try {
                // Request permission to use the API
                // preferences 파일에 이 패키지가 추가되어 있ㅇ다면 null 값을 반환함. null 일때 허용된거임.
                // null이 아니면 grantpermission 클래스로 이동해서 허용되는 과정을 거쳐야하고, 아니면 바로 onActivity 호줄.
                Intent i = mService.prepare(getActivity().getPackageName());
                if (i!=null) {
                    startActivityForResult(i, ICS_OPENVPN_PERMISSION);
                } else {
                    onActivityResult(ICS_OPENVPN_PERMISSION, Activity.RESULT_OK,null);
                }

            } catch (RemoteException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            mService = null;

        }
    };
    private String mStartUUID=null;

    private void bindService() {

        Intent icsopenvpnService = new Intent(IOpenVPNAPIService.class.getName());
        icsopenvpnService.setPackage("de.blinkt.openvpn");

        getActivity().bindService(icsopenvpnService, mConnection, Context.BIND_AUTO_CREATE);
    }

    protected void listVPNs() {

        try {
            List<APIVpnProfile> list = mService.getProfiles();
            String all="List:";
            // 5개만 뽑아옴.
            for(APIVpnProfile vp:list.subList(0, Math.min(5, list.size()))) {
                // List : v
                all = all + vp.mName + ":" + vp.mUUID + "\n";
            }

            if (list.size() > 5)
                all +="\n And some profiles....";

            if(list.size()> 0) {
                Button b= mStartVpn;
                // 여기서 자동으로 startVPN 메서드 처리하겠네.
                // 놉, 여기서 버튼이 활성화가 되어서 .vp 파일이 존재할 때만
                // 이 버튼 활성화가 되기 때문에 startVPN을 사용할 수 있는거지.
                b.setOnClickListener(this);
                b.setVisibility(View.VISIBLE);
                b.setText(list.get(0).mName);
                mStartUUID = list.get(0).mUUID;
            }



           mHelloWorld.setText(all);

        } catch (RemoteException e) {
            // TODO Auto-generated catch block
            mHelloWorld.setText(e.getMessage());
        }
    }

    private void unbindService() {
        getActivity().unbindService(mConnection);
    }

    @Override
    public void onStop() {
        super.onStop();
        unbindService();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.startVPN:
                try {
                    prepareStartProfile(START_PROFILE_BYUUID);

                } catch (RemoteException e) {
                    e.printStackTrace();
                }
                break;
            case R.id.disconnect:
                try {
                    mService.disconnect();
                } catch (RemoteException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
                break;
            case R.id.getMyIP:

                // Socket handling is not allowed on main thread
                new Thread() {

                    @Override
                    public void run() {
                        try {
                            String myip = getMyOwnIP();
                            Message msg = Message.obtain(mHandler,MSG_UPDATE_MYIP,myip);
                            msg.sendToTarget();
                        } catch (Exception e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }

                    }
                }.start();

                break;
            case R.id.startembedded:
                try {
                    prepareStartProfile(START_PROFILE_EMBEDDED);
                } catch (RemoteException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
                break;

            case R.id.addNewProfile:
            case R.id.addNewProfileEdit:
                int action = (v.getId() == R.id.addNewProfile) ? PROFILE_ADD_NEW : PROFILE_ADD_NEW_EDIT;
                try {
                    prepareStartProfile(action);
                } catch (RemoteException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            default:
                break;
        }

    }

    private void prepareStartProfile(int requestCode) throws RemoteException {
        Intent requestpermission = mService.prepareVPNService();
        if(requestpermission == null) {
            onActivityResult(requestCode, Activity.RESULT_OK, null);
        } else {
            // Have to call an external Activity since services cannot used onActivityResult
            startActivityForResult(requestpermission, requestCode);
        }
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK) {
            if(requestCode==START_PROFILE_EMBEDDED)
                startEmbeddedProfile(false, false, false);
            if(requestCode==START_PROFILE_BYUUID)
                try {
                    mService.startProfile(mStartUUID);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            if (requestCode == ICS_OPENVPN_PERMISSION) {
                listVPNs();
                try {
                    mService.registerStatusCallback(mCallback);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }

            }
            CheckBox startCB = getView().findViewById(R.id.startafterAdding);
            if (requestCode == PROFILE_ADD_NEW) {
                startEmbeddedProfile(true, false, startCB.isSelected());
            }
            else if (requestCode == PROFILE_ADD_NEW_EDIT) {
                startEmbeddedProfile(true, true, startCB.isSelected());
            }
        }
    };

    // 이건 그냥 내 IP textView 에 띄어주는거. 딱히 필요 없을듯.
    String getMyOwnIP() throws UnknownHostException, IOException, RemoteException,
            IllegalArgumentException, IllegalAccessException, InvocationTargetException, NoSuchMethodException
    {
        StringBuilder resp = new StringBuilder();

        URL url = new URL("https://icanhazip.com");
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
            while (true) {
                String line = in.readLine();
                if( line == null)
                    return resp.toString();
                resp.append(line);
            }
        } finally {
            urlConnection.disconnect();
        }
    }



    @Override
    public boolean handleMessage(Message msg) {
        if(msg.what == MSG_UPDATE_STATE) {
            mStatus.setText((CharSequence) msg.obj);
        } else if (msg.what == MSG_UPDATE_MYIP) {

            mMyIp.setText((CharSequence) msg.obj);
        }
        return true;
    }
}
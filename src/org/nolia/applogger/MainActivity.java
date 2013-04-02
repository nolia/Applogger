package org.nolia.applogger;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import org.nolia.applogger.R;

public class MainActivity extends Activity implements View.OnClickListener {

    @Override
    public void onClick(View v) {
        Intent logService = new Intent(this, AppLogService.class);
        if (toStartService) {
            startService(logService);
        } else {
            stopService(logService);
        }
        logButton.postDelayed(new Runnable() {
            @Override
            public void run() {
                changButtonText();
            }
        }, 500);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        logButton = (Button) findViewById(R.id.log_button);
        logButton.setOnClickListener(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        changButtonText();
    }

    private void changButtonText() {
        toStartService = !AppLogService.isCreated();
        logButton.setText((toStartService) ? R.string.start_logging
                : R.string.stop_logging);
    }

    private Button logButton;

    private boolean toStartService;

}

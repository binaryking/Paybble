package io.sudocode.paybble;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import com.firebase.client.ChildEventListener;
import com.firebase.client.DataSnapshot;
import com.firebase.client.Firebase;
import com.firebase.client.FirebaseError;
import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.util.PebbleDictionary;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.model.LatLng;
import com.google.maps.android.SphericalUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener, LocationListener {
    private static final String TAG = "MainActivity";
    public static final long UPDATE_INTERVAL_IN_MILLISECONDS = 1000;
    public static final long FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS =
            UPDATE_INTERVAL_IN_MILLISECONDS / 2;

    private GoogleApiClient mGoogleApiClient;
    private LocationRequest mLocationRequest;
    private Location mCurrentLocation;

    private String key;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Firebase.setAndroidContext(this);

        if (PebbleKit.isWatchConnected(getApplicationContext())) {
            registerPebbleConnectionReceivers();
            buildGoogleApiClient();
            initFirebase();
        } else {
            Toast.makeText(this, "No Pebble connected.", Toast.LENGTH_LONG)
                    .show();
            finish();
        }
    }

    private void registerPebbleConnectionReceivers() {
        PebbleKit.registerPebbleDisconnectedReceiver(getApplicationContext(),
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        Toast.makeText(MainActivity.this, "Pebble disconnected.", Toast.LENGTH_LONG)
                                .show();
                    }
                });
    }

    private void initFirebase() {
        Firebase firebase = new Firebase("https://paybble.firebaseio.com/orders");

        firebase.addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                if (dataSnapshot.child("customerName").exists())
                    key = dataSnapshot.getKey();
            }

            @Override
            public void onChildChanged(DataSnapshot dataSnapshot, String s) {
                if (dataSnapshot.getKey().equals(key)) {
                    double customer_latitude = (double)dataSnapshot.child("location").child("latitude").getValue();
                    double customer_longitude = (double)dataSnapshot.child("location").child("longitude").getValue();

                    LatLng customer = new LatLng(customer_latitude, customer_longitude);
                    LatLng vendor = new LatLng(mCurrentLocation.getLatitude(), mCurrentLocation.getLongitude());

                    int heading = (int)SphericalUtil.computeHeading(customer, vendor);
                    heading += 180;
                    Log.d("heading: ", Integer.toString(heading));
                    List<LatLng> latLngs = new ArrayList<>();
                    latLngs.add(vendor);
                    latLngs.add(customer);
                    int distance = (int)SphericalUtil.computeLength(latLngs);
                    Log.d("distance: ", Integer.toString(distance));
                    String customer_name = (String)dataSnapshot.child("customerName").getValue();
                    String amount = Double.toString((double)dataSnapshot.child("amount").getValue());

                    PebbleDictionary dictionary = new PebbleDictionary();
                    dictionary.addString(11, customer_name);
                    dictionary.addString(12, amount + " / 0.007 BTC");
                    dictionary.addInt32(13, distance);
                    dictionary.addInt32(14, heading);
                    PebbleKit.sendDataToPebble(getApplicationContext(),
                            UUID.fromString(getString(R.string.paybble_uuid)), dictionary);
                }
            }

            @Override
            public void onChildRemoved(DataSnapshot dataSnapshot) {}

            @Override
            public void onChildMoved(DataSnapshot dataSnapshot, String s) {}

            @Override
            public void onCancelled(FirebaseError firebaseError) {

            }
        });
    }

    private synchronized void buildGoogleApiClient() {
        Log.i(TAG, "Building GoogleApiClient");
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();
        createLocationRequest();
    }

    private void createLocationRequest() {
        mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(UPDATE_INTERVAL_IN_MILLISECONDS);
        mLocationRequest.setFastestInterval(FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    }

    public void startLocationUpdates() {
        LocationServices.FusedLocationApi.requestLocationUpdates(
                mGoogleApiClient, mLocationRequest, this);
    }

    public void stopLocationUpdates() {
        LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient,
                this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mGoogleApiClient.connect();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mGoogleApiClient.isConnected()) {
            startLocationUpdates();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mGoogleApiClient.isConnected()) {
            stopLocationUpdates();
        }
    }

    @Override
    protected void onStop() {
        mGoogleApiClient.disconnect();

        super.onStop();
    }


    @Override
    public void onConnected(Bundle connectionHint) {
        Log.i(TAG, "Connected to GoogleApiClient");

        if (mCurrentLocation == null) {
            mCurrentLocation = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
        }

        startLocationUpdates();
    }

    @Override
    public void onLocationChanged(Location location) {
        Log.i(TAG, "location changed!");

        mCurrentLocation = location;
        if (key != null) {
            Firebase firebase = new Firebase("https://paybble.firebaseio.com/orders");
            firebase = firebase.child(key);

            firebase.child("vendor_location").child("latitude")
                    .setValue(mCurrentLocation.getLatitude());
            firebase.child("vendor_location").child("longitude")
                    .setValue(mCurrentLocation.getLongitude());
        }
    }

    @Override
    public void onConnectionSuspended(int cause) {
        Log.i(TAG, "Connection suspended");
        mGoogleApiClient.connect();
    }

    @Override
    public void onConnectionFailed(ConnectionResult result) {
        Log.i(TAG, "Connection failed: ConnectionResult.getErrorCode() = " + result.getErrorCode());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}

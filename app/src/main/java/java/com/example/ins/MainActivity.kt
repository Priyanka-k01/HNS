package java.com.example.ins


import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser


class MainActivity : AppCompatActivity() {

    private var Button1: Button? = null
    private lateinit var auth: FirebaseAuth


    private var Button2: Button? = null
    /*private var Button3: Button? = null
    private var Button4: Button? = null*/
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        auth = FirebaseAuth.getInstance()

        initViews()
        requestForPermission()


    }

    private fun requestForPermission() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (ActivityCompat.checkSelfPermission(
                this,
                permissions[0]
            ) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                this,
                permissions[1]
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, permissions, 100)


        }

    }

    private fun initViews() {
        Button1 = findViewById(R.id.id1)


        Button2 = findViewById(R.id.id2)
        /*Button3 = findViewById(R.id.id3)
        Button4 = findViewById(R.id.id4)*/

        Button1!!.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)

        }
        Button2!!.setOnClickListener {
            val intent = Intent(this, User::class.java)
            startActivity(intent)

        }
        /*Button3!!.setOnClickListener {

        }
        Button4!!.setOnClickListener {

        }*/
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String?>,
        grantResults: IntArray,
        deviceId: Int
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults, deviceId)
        if(requestCode == 100){
            if(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED ){
                Toast.makeText(this,"Permission Granted", Toast.LENGTH_SHORT).show()
            }else{
                Toast.makeText(this,"Permission Denied", Toast.LENGTH_SHORT).show()
            }
            }
        }

    }

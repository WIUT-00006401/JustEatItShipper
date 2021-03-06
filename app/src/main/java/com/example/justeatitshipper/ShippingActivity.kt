package com.example.justeatitshipper

import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.location.Location
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.bumptech.glide.Glide
import com.example.justeatitshipper.Common.Common
import com.example.justeatitshipper.Common.LatLngInterpolator
import com.example.justeatitshipper.Common.MarkerAnimation
import com.example.justeatitshipper.Model.ShippingOrderModel
import com.example.justeatitshipper.Remote.IGoogleApi
import com.example.justeatitshipper.Remote.RetrofitClient
import com.google.android.gms.common.api.Status
import com.google.android.gms.location.*

import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.libraries.places.widget.AutocompleteSupportFragment
import com.google.android.libraries.places.widget.listener.PlaceSelectionListener
import com.google.firebase.database.FirebaseDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.single.PermissionListener
import io.paperdb.Paper
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_shipping.*
import org.json.JSONObject
import java.lang.StringBuilder
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*
import java.util.jar.Manifest
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class ShippingActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private var shipperMarker: Marker?=null
    private var shippingOrderModel: ShippingOrderModel?=null

    var isInit=false
    var previousLocation: Location?=null

    private var handler: Handler?=null
    private var index:Int=-1
    private var next:Int=0
    private var startPosition:LatLng?= LatLng(0.0,0.0)
    private var endPosition:LatLng?=LatLng(0.0,0.0)
    private var v:Float=0F
    private var lat:Double=-1.0
    private var lng:Double=-1.0

    private var blackPolyline:Polyline?=null
    private var greyPolyline:Polyline?=null
    private var polylineOptions:PolylineOptions?=null
    private var blackPolylineOptions:PolylineOptions?=null
    private var redPolyline:Polyline?=null
    private var yellowPolyline:Polyline?=null


    private var polylineList:List<LatLng> = ArrayList<LatLng>()
    private var iGoogleApi: IGoogleApi?=null
    private var compositeDisposable = CompositeDisposable()

    private lateinit var places_fragment: AutocompleteSupportFragment
    private lateinit var placesClient: PlacesClient
    private val placeFields = Arrays.asList(
        Place.Field.ID,
        Place.Field.NAME,
        Place.Field.ADDRESS,
        Place.Field.LAT_LNG)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shipping)

        iGoogleApi = RetrofitClient.instance!!.create(IGoogleApi::class.java)

        initPlaces()

        setupPlaceAutocomplete()

        buildLocationRequest()
        buildLocationCallback()





        //checked true
        Dexter.withActivity(this)
            .withPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            .withListener(object: PermissionListener {
                override fun onPermissionGranted(response: PermissionGrantedResponse?) {
                    //Obtain the SupportMapFragment and get notified when the map is ready to be used
                    val mapFragment = supportFragmentManager
                        .findFragmentById(R.id.map) as SupportMapFragment
                        //.findFragmentById(com.google.android.gms.location.R.id.map) as SupportMapFragment
                    mapFragment.getMapAsync(this@ShippingActivity)

                    fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this@ShippingActivity)
                    fusedLocationProviderClient.requestLocationUpdates(locationRequest,locationCallback,
                        Looper.myLooper())
                }

                override fun onPermissionRationaleShouldBeShown(
                    permission: PermissionRequest?,
                    token: PermissionToken?
                ) {

                }

                override fun onPermissionDenied(response: PermissionDeniedResponse?) {
                    Toast.makeText(this@ShippingActivity,"You must enable this permission",Toast.LENGTH_SHORT).show()
                }

            }).check()

        initViews()
    }

    private fun setupPlaceAutocomplete() {

        places_fragment = supportFragmentManager
            //.findFragmentById(com.google.android.gms.location.R.id.places_autocomplete_fragment) as AutocompleteSupportFragment
            .findFragmentById(R.id.places_autocomplete_fragment) as AutocompleteSupportFragment
        places_fragment.setPlaceFields(placeFields)
        places_fragment.setOnPlaceSelectedListener(object: PlaceSelectionListener {
            override fun onPlaceSelected(place: Place) {
                drawRoutes(place)

            }

            override fun onError(p0: Status) {
                Toast.makeText(this@ShippingActivity,""+p0.statusMessage,Toast.LENGTH_SHORT).show()
            }

        })
    }

    private fun initPlaces() {
        //Places.initialize(this,getString(com.google.android.gms.location.R.string.google_maps_key))
        Places.initialize(this,getString(R.string.google_maps_key))
        placesClient = Places.createClient(this)
    }

    private fun initViews() {
        btn_start_trip.setOnClickListener{
            val data = Paper.book().read<String>(Common.SHIPPING_DATA)
            Paper.book().write(Common.TRIP_START,data)
            btn_start_trip.isEnabled = false//Deactive after click

            shippingOrderModel = Gson().fromJson(data,object :TypeToken<ShippingOrderModel?>(){}.type)

            fusedLocationProviderClient.lastLocation.addOnSuccessListener { location->

                compositeDisposable.add(iGoogleApi!!.getDirections("driving", "less_driving",
                Common.buildLocationString(location),
                StringBuilder().append(shippingOrderModel!!.orderModel!!.lat)
                    .append(",")
                    .append(shippingOrderModel!!.orderModel!!.lng).toString(),
                getString(R.string.google_maps_key))!!
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({s->

                        //Get Estimate time from API
                        var estimateTime = "UNKNOWN"
                        val jsonObject = JSONObject(s)
                        val routes = jsonObject.getJSONArray("routes")
                        val `object` = routes.getJSONObject(0)
                        val legs = `object`.getJSONArray("legs")
                        val legsObject = legs.getJSONObject(0)

                        //Time
                        val time = legsObject.getJSONObject("duration")
                        estimateTime = time.getString("text")

                        val update_data = HashMap<String,Any>()
                        update_data.put("currentLat",location.latitude)
                        update_data.put("currentLng", location.longitude)
                        update_data.put("estimateTime",estimateTime)

                        FirebaseDatabase.getInstance()
                            .getReference(Common.SHIPPING_ORDER_REF)
                            .child(shippingOrderModel!!.key!!)
                            .updateChildren(update_data)
                            .addOnFailureListener{e->
                                Toast.makeText(this,e.message,Toast.LENGTH_SHORT).show()
                            }
                            .addOnSuccessListener { aVoid->
                                //Show directions from shipper to order's location after start trip
                                drawRoutes(data)
                            }

                    }, {t:Throwable? ->
                        Toast.makeText(this@ShippingActivity,t!!.message,Toast.LENGTH_SHORT).show()
                    }))

            }

            //Show directions from shipper to order's location after start trip
            drawRoutes(data)
        }

        btn_show.setOnClickListener{
            if (expandable_layout.isExpanded)
                btn_show.text = "SHOW"
            else
                btn_show.text = "HIDE"
            expandable_layout.toggle()
        }

        btn_call.setOnClickListener{
            if (shippingOrderModel != null)
            {
                if (ActivityCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.CALL_PHONE
                    ) != PackageManager.PERMISSION_GRANTED)
                {
                    Dexter.withActivity(this)
                        .withPermission(android.Manifest.permission.CALL_PHONE)
                        .withListener(object :PermissionListener{
                            override fun onPermissionGranted(response: PermissionGrantedResponse?) {

                            }

                            override fun onPermissionRationaleShouldBeShown(
                                permission: PermissionRequest?,
                                token: PermissionToken?
                            ) {

                            }

                            override fun onPermissionDenied(response: PermissionDeniedResponse?) {
                                Toast.makeText(this@ShippingActivity, "You must enable this permission to CALL", Toast.LENGTH_SHORT).show()
                            }

                        }).check()

                    return@setOnClickListener
                }

                val intent = Intent(Intent.ACTION_CALL)
                intent.data = (Uri.parse(StringBuilder("tel:").append(shippingOrderModel!!.orderModel!!.userPhone!!).toString()))

                startActivity(intent)
            }
        }
    }

    private fun setShippingOrderModel() {
        Paper.init(this)

        var data:String?=""
        if (TextUtils.isEmpty(Paper.book().read(Common.TRIP_START)))
        {
            data = Paper.book().read<String>(Common.SHIPPING_DATA)
            btn_start_trip.isEnabled = true
        }
        else
        {
            data = Paper.book().read<String>(Common.TRIP_START)
            btn_start_trip.isEnabled = false
        }
//till here
        if (!TextUtils.isEmpty(data))
        {

            drawRoutes(data)

            shippingOrderModel = Gson()
                .fromJson<ShippingOrderModel>(data,object: TypeToken<ShippingOrderModel>(){}.type)

            if (shippingOrderModel != null)
            {
                Common.setSpanStringColor("Name: ",
                    shippingOrderModel!!.orderModel!!.userName,
                    txt_name,
                    Color.parseColor("#333639"))

                Common.setSpanStringColor("Address: ",
                    shippingOrderModel!!.orderModel!!.shippingAddress,
                    txt_address,
                    Color.parseColor("#673ab7"))

                Common.setSpanStringColor("No: ",
                    shippingOrderModel!!.orderModel!!.key,
                    txt_order_number,
                    Color.parseColor("#795548"))

                txt_date!!.text = StringBuilder().append(
                    SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(
                    shippingOrderModel!!.orderModel!!.createDate
                ))

                Glide.with(this)
                    .load(shippingOrderModel!!.orderModel!!.cartItemList!![0]!!.foodImage)
                    .into(img_food_image)
            }
        }
        else
        {
            Toast.makeText(this,"Shipping order model is null", Toast.LENGTH_SHORT).show()
        }
    }

    //checked true 59
    private fun drawRoutes(place:Place) {


        mMap.addMarker(MarkerOptions()
            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW))
            .title(place.name)
            .snippet(place.address)
            .position(place.latLng!!))

        fusedLocationProviderClient.lastLocation
            .addOnFailureListener{e->Toast.makeText(this@ShippingActivity,""+e.message,
                Toast.LENGTH_SHORT).show()}
            .addOnSuccessListener { location ->
                val to = StringBuilder().append(place.latLng!!.latitude)
                    .append(",")
                    .append(place.latLng!!.latitude).toString()
                val from = StringBuilder().append(location.latitude)
                    .append(",")
                    .append(location.longitude).toString()

                compositeDisposable.add(iGoogleApi!!.getDirections("driving", "less_driving",
                    from, to,
                    //getString(com.google.android.gms.location.R.string.google_maps_key))!!
                    getString(R.string.google_maps_key))!!
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ s->
                        try {
                            val jsonObject = JSONObject(s)
                            val jsonArray = jsonObject.getJSONArray("routes")
                            for (i in 0 until jsonArray.length())
                            {
                                val route  = jsonArray.getJSONObject(i)
                                val poly = route.getJSONObject("overview_polyline")
                                val polyline = poly.getString("points")
                                polylineList = Common.decodePoly(polyline)
                            }

                            polylineOptions = PolylineOptions()
                            polylineOptions!!.color(Color.YELLOW)
                            polylineOptions!!.width(12.0f)
                            polylineOptions!!.startCap(SquareCap())
                            polylineOptions!!.endCap(SquareCap())
                            polylineOptions!!.jointType(JointType.ROUND)
                            polylineOptions!!.addAll(polylineList)
                            yellowPolyline = mMap.addPolyline(polylineOptions)


                        }catch (e:Exception)
                        {
                            Log.d("DEBUG",e.message)
                        }
                    }, {throwable ->
                        Toast.makeText(this@ShippingActivity,""+throwable.message,Toast.LENGTH_SHORT).show()
                    }))
            }
    }



    //checked true whole function correct 58
    private fun drawRoutes(data: String?) {


        val shippingOrderModel = Gson()
            .fromJson<ShippingOrderModel>(data,object:TypeToken<ShippingOrderModel>(){}.type)

        mMap.addMarker(MarkerOptions()
            //.icon(BitmapDescriptorFactory.fromResource(com.google.android.gms.location.R.drawable.box))
            .icon(BitmapDescriptorFactory.fromResource(R.drawable.box))
            .title(shippingOrderModel.orderModel!!.userName)
            .snippet(shippingOrderModel.orderModel!!.shippingAddress)
            .position(LatLng(shippingOrderModel.orderModel!!.lat, shippingOrderModel.orderModel!!.lng)))

        fusedLocationProviderClient.lastLocation
            .addOnFailureListener{e->Toast.makeText(this@ShippingActivity,""+e.message,
                Toast.LENGTH_SHORT).show()}
            .addOnSuccessListener { location ->
                val to = StringBuilder().append(shippingOrderModel.orderModel!!.lat)
                    .append(",")
                    .append(shippingOrderModel.orderModel!!.lng).toString()
                val from = StringBuilder().append(location.latitude)
                    .append(",")
                    .append(location.longitude).toString()

                compositeDisposable.add(iGoogleApi!!.getDirections("driving", "less_driving",
                    from, to,
                    //getString(com.google.android.gms.location.R.string.google_maps_key))!!
                    getString(R.string.google_maps_key))!!
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ s->
                        try {
                            val jsonObject = JSONObject(s)
                            val jsonArray = jsonObject.getJSONArray("routes")
                            for (i in 0 until jsonArray.length())
                            {
                                val route  = jsonArray.getJSONObject(i)
                                val poly = route.getJSONObject("overview_polyline")
                                val polyline = poly.getString("points")
                                polylineList = Common.decodePoly(polyline)
                            }

                            polylineOptions = PolylineOptions()
                            polylineOptions!!.color(Color.RED)
                            polylineOptions!!.width(12.0f)
                            polylineOptions!!.startCap(SquareCap())
                            polylineOptions!!.endCap(SquareCap())
                            polylineOptions!!.jointType(JointType.ROUND)
                            polylineOptions!!.addAll(polylineList)
                            redPolyline = mMap.addPolyline(polylineOptions)


                        }catch (e:Exception)
                        {
                            Log.d("DEBUG",e.message)
                        }
                    }, {throwable ->
                        Toast.makeText(this@ShippingActivity,""+throwable.message,Toast.LENGTH_SHORT).show()
                    }))
            }
    }


    private fun buildLocationCallback() {

        locationCallback = object:LocationCallback(){
            override fun onLocationResult(p0: LocationResult?) {
                super.onLocationResult(p0)
                val locationShipper = LatLng(p0!!.lastLocation.latitude,
                    p0!!.lastLocation.longitude)

                updateLocation(p0.lastLocation)

                //Checked true 55-video
                if (shipperMarker==null)
                {
                    val height = 80
                    val width = 80
                    val bitmapDrawable = ContextCompat.getDrawable(this@ShippingActivity,
                        R.drawable.point)
                        //com.google.android.gms.location.R.drawable.point)
                    val b=bitmapDrawable!!.toBitmap()
                    val smallMarker = Bitmap.createScaledBitmap(b,width,height,false)
                    shipperMarker = mMap!!.addMarker(MarkerOptions()
                        .icon(BitmapDescriptorFactory.fromBitmap(smallMarker))
                        .position(locationShipper)
                        .title("You"))

                    mMap!!.animateCamera(CameraUpdateFactory.newLatLngZoom(locationShipper,18f))
                }






                //Checked true 55-video
                if (isInit && previousLocation != null)
                {
                    val from = StringBuilder()
                        .append(previousLocation!!.latitude)
                        .append(",")
                        .append(previousLocation!!.longitude)
                    val to = StringBuilder()
                        .append(locationShipper.latitude)
                        .append(",")
                        .append(locationShipper.longitude)

                    moveMarkerAnimation(shipperMarker,from,to)
                    previousLocation = p0.lastLocation
                }
                if (!isInit)
                {
                    isInit = true
                    previousLocation = p0.lastLocation
                }
                //till here
            }
        }
    }

    private fun updateLocation(lastLocation:Location?){
        val data = Paper.book().read<String>(Common.TRIP_START)
        if (!TextUtils.isEmpty(data))
        {
            val shippingOrder = Gson().fromJson<ShippingOrderModel>(data,object :TypeToken<ShippingOrderModel>(){}.type)
            if (shippingOrder != null)
            {
                compositeDisposable.add(iGoogleApi!!.getDirections("driving", "less_driving",
                    Common.buildLocationString(lastLocation),
                    StringBuilder().append(shippingOrderModel!!.orderModel!!.lat)
                        .append(",")
                        .append(shippingOrderModel!!.orderModel!!.lng).toString(),
                    getString(R.string.google_maps_key))!!
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({s->

                        //Get Estimate time from API
                        var estimateTime = "UNKNOWN"
                        val jsonObject = JSONObject(s)
                        val routes = jsonObject.getJSONArray("routes")
                        val `object` = routes.getJSONObject(0)
                        val legs = `object`.getJSONArray("legs")
                        val legsObject = legs.getJSONObject(0)

                        //Time
                        val time = legsObject.getJSONObject("duration")
                        estimateTime = time.getString("text")

                        val update_data = HashMap<String,Any>()
                        update_data.put("currentLat",lastLocation!!.latitude)
                        update_data.put("currentLng", lastLocation!!.longitude)
                        update_data.put("estimateTime",estimateTime)

                        FirebaseDatabase.getInstance()
                            .getReference(Common.SHIPPING_ORDER_REF)
                            .child(shippingOrderModel!!.key!!)
                            .updateChildren(update_data)
                            .addOnFailureListener{e->
                                Toast.makeText(this,e.message,Toast.LENGTH_SHORT).show()
                            }


                    }, {t:Throwable? ->
                        Toast.makeText(this@ShippingActivity,t!!.message,Toast.LENGTH_SHORT).show()
                    }))
            }
        }
        else
        {
            Toast.makeText(this, "Please press START_TRIP", Toast.LENGTH_SHORT).show()
        }
    }

    private fun moveMarkerAnimation(
        marker: Marker?,
        from: StringBuilder,
        to: StringBuilder
    ) {


        compositeDisposable.add(iGoogleApi!!.getDirections("driving",
            "less_driving",
            from.toString(),
            to.toString(),
            //getString(com.google.android.gms.location.R.string.google_maps_key))!!
            getString(R.string.google_maps_key))!!
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ s->
                Log.d("DEBUG",s)
                try {
                    val jsonObject = JSONObject(s)
                    val jsonArray = jsonObject.getJSONArray("routes")
                    for (i in 0 until jsonArray.length())
                    {
                        val route  = jsonArray.getJSONObject(i)
                        val poly = route.getJSONObject("overview_polyline")
                        val polyline = poly.getString("points")
                        polylineList = Common.decodePoly(polyline)
                    }
//till here
                    polylineOptions = PolylineOptions()
                    polylineOptions!!.color(Color.GRAY)
                    polylineOptions!!.width(5.0f)
                    polylineOptions!!.startCap(SquareCap())
                    polylineOptions!!.endCap(SquareCap())
                    polylineOptions!!.jointType(JointType.ROUND)
                    polylineOptions!!.addAll(polylineList)
                    greyPolyline = mMap.addPolyline(polylineOptions)

                    blackPolylineOptions = PolylineOptions()
                    blackPolylineOptions!!.color(Color.GRAY)
                    blackPolylineOptions!!.width(5.0f)
                    blackPolylineOptions!!.startCap(SquareCap())
                    blackPolylineOptions!!.endCap(SquareCap())
                    blackPolylineOptions!!.jointType(JointType.ROUND)
                    blackPolylineOptions!!.addAll(polylineList)
                    blackPolyline = mMap.addPolyline(blackPolylineOptions)

                    //Animator
                    val polylineAnimator = ValueAnimator.ofInt(0,100)
                    polylineAnimator.setDuration(2000)
                    polylineAnimator.setInterpolator (LinearInterpolator())
                    polylineAnimator.addUpdateListener { valueAnimator ->
                        val points = greyPolyline!!.points
                        val percentValue = Integer.parseInt(valueAnimator.animatedValue.toString())
                        val size = points.size
                        val newPoints = (size*(percentValue/100.0f)).toInt()
                        val p = points.subList(0, newPoints)
                        blackPolyline!!.points = p
                    }
                    polylineAnimator.start()
//till here
                    //Car moving
                    index = -1
                    next = 1
                    val r = object : Runnable{
                        override fun run() {
                            if(index<polylineList.size-1)
                            {
                                index++
                                next = index+1
                                startPosition = polylineList[index]
                                endPosition = polylineList[next]
                            }

                            val valueAnimator = ValueAnimator.ofInt(0,1)
                            valueAnimator.setDuration(1500)
                            valueAnimator.setInterpolator (LinearInterpolator())
                            valueAnimator.addUpdateListener { valueAnimator ->
                                v = valueAnimator.animatedFraction
                                lat = v * endPosition!!.latitude + (1-v)*startPosition!!.latitude
                                lng = v * endPosition!!.longitude + (1-v)*startPosition!!.longitude

                                val newPos = LatLng(lat,lng)
                                marker!!.position = newPos
                                marker!!.setAnchor(0.5f,0.5f)
                                marker!!.rotation = Common.getBearing(startPosition!!,newPos)

                                mMap.moveCamera(CameraUpdateFactory.newLatLng(marker.position)) //Fixed
                            }

                            valueAnimator.start()
                            if (index < polylineList.size-2)
                                handler!!.postDelayed(this,1500)
                        }

                    }


                    handler = Handler()
                    handler!!.postDelayed(r, 1500)



                }
                catch (e:Exception)
                {
                    Log.d("DEBUG",e.message)
                }
            }, {throwable ->
                Toast.makeText(this@ShippingActivity,""+throwable.message,Toast.LENGTH_SHORT).show()
            }))
    }

    private fun buildLocationRequest() {
        locationRequest = LocationRequest()
        locationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        locationRequest.setInterval(15000)
        locationRequest.setFastestInterval(10000)
        locationRequest.setSmallestDisplacement(20f)
    }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        setShippingOrderModel()

        mMap!!.uiSettings.isZoomControlsEnabled = true
        try {
            val success = googleMap.setMapStyle(
                MapStyleOptions.loadRawResourceStyle(this,
                R.raw.map_style))
                //com.google.android.gms.location.R.raw.map_style))
            if(!success)
                Log.d("DJDEV", "Failed to load map style")
        }catch (ex: Resources.NotFoundException)
        {
            Log.d("DJDEV", "Not found json string for map style")
        }
    }

    //checked true
    override fun onDestroy() {
        fusedLocationProviderClient.removeLocationUpdates(locationCallback)
        compositeDisposable.clear()
        super.onDestroy()
    }
}

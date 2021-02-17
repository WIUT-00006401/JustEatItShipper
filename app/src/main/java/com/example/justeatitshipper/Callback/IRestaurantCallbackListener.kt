package com.example.justeatitshipper.Callback

import com.example.justeatitshipper.Model.RestaurantModel

interface IRestaurantCallbackListener {
    fun onRestaurantLoadSuccess(restaurantList:List<RestaurantModel>)
    fun onRestaurantLoadFailed(message:String)
}
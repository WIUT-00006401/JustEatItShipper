package com.example.justeatitshipper.Callback

import com.example.justeatitshipper.Model.ShippingOrderModel

interface IShippingOrderCallbackListener {
    fun onShippingOrderLoadSuccess(shippingOrders:List<ShippingOrderModel>)
    fun onShippingOrderFailed(message:String)
}
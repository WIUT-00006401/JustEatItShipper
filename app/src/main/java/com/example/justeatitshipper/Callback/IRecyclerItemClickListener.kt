package com.example.justeatitshipper.Callback

import android.view.View

interface IRecyclerItemClickListener {
    fun onItemClick(view: View, pos:Int)
}
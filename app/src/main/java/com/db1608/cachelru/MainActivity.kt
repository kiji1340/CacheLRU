package com.db1608.cachelru

import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatActivity
import com.db1608.cache.CacheLRU
import com.db1608.cache.CacheLRUBuilder
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity(), View.OnClickListener, CompoundButton.OnCheckedChangeListener {

    private var encrypted = false
    override fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean) {
        encrypted = isChecked
    }

    override fun onClick(v: View?) {
        when (v) {
            initializeBtn -> {

                CacheLRUBuilder.configure(8152)
                    .initialize()
            }

            saveBtn -> {
                val data = Data(nameEd.text.toString(), contentEd.text.toString())
                CacheLRU.put("data", data)
            }

            clearBtn -> {

            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        encryptCB.setOnCheckedChangeListener(this)
        saveBtn.setOnClickListener(this)
        clearBtn.setOnClickListener(this)
    }
}

package com.db1608.cachelru

import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.solver.Cache
import com.db1608.cache.CacheLRU
import com.db1608.cache.CacheLRUBuilder
import com.db1608.cache.coroutine.CoroutineCache
import kotlinx.android.synthetic.main.activity_main.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), View.OnClickListener, CompoundButton.OnCheckedChangeListener {
    companion object{
        private const val DATA = "DATA"
    }
    private var encrypted = false
    override fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean) {
        encrypted = isChecked
    }

    override fun onClick(v: View?) {
        when (v) {

            saveBtn -> {
                val data = Data(nameEd.text.toString(), contentEd.text.toString())
                if (expiredEd.text.isEmpty()) {
                    CacheLRU.put(DATA, data).execute()
                } else {
                    CacheLRU.put(DATA, data).setExpiry(expiredEd.text.toString().toLong(), TimeUnit.MINUTES).execute()
                }
            }

            clearBtn -> {
                CacheLRU.clear()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        encryptCB.setOnCheckedChangeListener(this)
        saveBtn.setOnClickListener(this)
        clearBtn.setOnClickListener(this)
        val data = CoroutineCache.getAsync(DATA, Data::class.java)


    }
}

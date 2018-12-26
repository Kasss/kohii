/*
 * Copyright (c) 2018 Nam Nguyen, nam@ene.im
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kohii.v1.sample.ui.mix

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kohii.v1.sample.R
import kohii.v1.sample.common.BaseFragment
import okio.Okio

/**
 * @author eneim (2018/10/30).
 */
class MixMediaFragment : BaseFragment() {

  companion object {
    fun newInstance() = MixMediaFragment()
  }

  lateinit var items: List<Item>

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val asset = requireActivity().assets
    val type = Types.newParameterizedType(List::class.java, Item::class.java)
    val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    val adapter: JsonAdapter<List<Item>> = moshi.adapter(type)
    items = adapter.fromJson(Okio.buffer(Okio.source(asset.open("medias.json"))))!!
  }

  override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View? {
    return inflater.inflate(R.layout.fragment_recycler_view, parent, false)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    (view.findViewById(R.id.recyclerView) as RecyclerView).also {
      it.setHasFixedSize(true)
      it.adapter = ItemsAdapter(items)
    }
  }
}
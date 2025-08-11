package com.example.smtsmessenger

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ContactsAdapter(private val onContactClick: (MainActivity.Contact) -> Unit) : RecyclerView.Adapter<ContactsAdapter.ContactViewHolder>() {
    
    private val contacts = mutableListOf<MainActivity.Contact>()
    
    class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val checkBox: CheckBox = itemView.findViewById(R.id.contactCheckBox)
        val idText: TextView = itemView.findViewById(R.id.contactIdText)
        val statusText: TextView = itemView.findViewById(R.id.contactStatusText)
        val keyHashText: TextView = itemView.findViewById(R.id.contactKeyHashText)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val contact = contacts[position]
        
        holder.checkBox.isChecked = contact.isSelected
        holder.idText.text = contact.id
        holder.statusText.text = contact.status
        holder.keyHashText.text = contact.keyHash
        
        // Меняем цвет статуса в зависимости от состояния
        when (contact.status) {
            "established" -> {
                holder.statusText.setTextColor(holder.itemView.context.getColor(android.R.color.holo_green_dark))
            }
            "pending" -> {
                holder.statusText.setTextColor(holder.itemView.context.getColor(android.R.color.holo_orange_dark))
            }
            else -> {
                holder.statusText.setTextColor(holder.itemView.context.getColor(android.R.color.darker_gray))
            }
        }
        
        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
            contacts[position] = contact.copy(isSelected = isChecked)
        }
        
        holder.itemView.setOnClickListener {
            onContactClick(contact)
        }
    }
    
    override fun getItemCount(): Int = contacts.size
    
    fun updateContacts(newContacts: List<MainActivity.Contact>) {
        contacts.clear()
        contacts.addAll(newContacts)
        notifyDataSetChanged()
    }
    
    fun getSelectedContacts(): List<String> {
        return contacts.filter { it.isSelected }.map { it.id }
    }
}
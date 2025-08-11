package com.example.smtsmessenger

import android.graphics.Color
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.*

class MessagesAdapter : RecyclerView.Adapter<MessagesAdapter.MessageViewHolder>() {
    
    private val messages = mutableListOf<MainActivity.Message>()
    
    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageText: TextView = itemView.findViewById(R.id.messageText)
        val timestampText: TextView = itemView.findViewById(R.id.timestampText)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        
        holder.messageText.text = message.text
        
        // Форматируем время
        val date = Date(message.timestamp)
        holder.timestampText.text = DateFormat.format("HH:mm:ss", date)
        
        // Окрашиваем сообщения об ошибках и системные сообщения
        when {
            message.text.contains("[!]") -> {
                holder.messageText.setTextColor(Color.RED)
            }
            message.text.contains("[DH]") -> {
                holder.messageText.setTextColor(Color.BLUE)
            }
            message.text.contains("[+]") -> {
                holder.messageText.setTextColor(Color.GREEN)
            }
            message.text.contains("Я →") -> {
                holder.messageText.setTextColor(Color.rgb(0, 100, 0)) // Темно-зеленый для отправленных
            }
            message.text.contains("от") -> {
                holder.messageText.setTextColor(Color.rgb(0, 0, 150)) // Темно-синий для полученных
            }
            else -> {
                holder.messageText.setTextColor(Color.BLACK)
            }
        }
    }
    
    override fun getItemCount(): Int = messages.size
    
    fun addMessage(message: MainActivity.Message) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }
    
    fun clearMessages() {
        messages.clear()
        notifyDataSetChanged()
    }
}
package com.paysetu.offlinevault

// 1. Add this to define the types of messages
enum class PacketType {
    PAYMENT,         // Actual money being sent
    ACKNOWLEDGEMENT, // Just a "Success" message (The Ghost Fix)
    HANDSHAKE        // Initial connection
}

// 2. Update your main data class
data class SecurePacket(
    val type: PacketType,
    val amount: Long,      // This will be 0 for ACKNOWLEDGEMENT
    val senderName: String,
    val timestamp: Long,
    val transactionId: String
)
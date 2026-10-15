package com.iotrix.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iotrix.app.data.Contact
import java.util.UUID

@Composable
fun ContactsScreen(
    contacts: List<Contact>,
    onSave: (Contact) -> Unit,
    onDelete: (Contact) -> Unit,
) {
    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Contact?>(null) }
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Emergency contacts", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(12.dp))
                Text("Alert these people in an emergency.", fontWeight = FontWeight.Bold)
                Text("They get an SMS with your location if a serious fall is confirmed.", color = C.Sub)
            }
            if (contacts.isEmpty()) {
                item {
                    AppCard {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.PersonAdd, null, tint = C.Sub, modifier = Modifier.size(34.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("No contacts yet. Add someone who should be alerted.", color = C.Sub)
                        }
                    }
                }
            }
            items(contacts, key = { it.id }) { c ->
                var menu by remember { mutableStateOf(false) }
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.name)
                        Spacer(Modifier.size(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                if (c.relation.isNotBlank()) c.relation + "  " + c.phone else c.phone,
                                color = C.Sub,
                            )
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Options", tint = C.Sub) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Call") }, onClick = {
                                    menu = false
                                    ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.phone}")))
                                })
                                DropdownMenuItem(text = { Text("Edit") }, onClick = {
                                    menu = false; editing = c; showForm = true
                                })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = {
                                    menu = false; onDelete(c)
                                })
                            }
                        }
                    }
                }
            }
        }
        AppButton(
            "Add contact", { editing = null; showForm = true },
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), icon = Icons.Filled.Add,
        )
    }

    if (showForm) {
        ContactDialog(
            initial = editing,
            onDismiss = { showForm = false },
            onSave = { showForm = false; onSave(it) },
        )
    }
}

@Composable
private fun ContactDialog(initial: Contact?, onDismiss: () -> Unit, onSave: (Contact) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var relation by remember { mutableStateOf(initial?.relation ?: "") }
    var phone by remember { mutableStateOf(initial?.phone ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add contact" else "Edit contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(relation, { relation = it }, label = { Text("Relationship") }, singleLine = true)
                OutlinedTextField(
                    phone, { phone = it }, label = { Text("Phone number") }, singleLine = true,
                    placeholder = { Text("+94 77 123 4567") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                error?.let { Text(it, color = C.Red, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    name.isBlank() -> error = "Enter the contact's name."
                    phone.filter { it.isDigit() }.length < 7 -> error = "Enter a valid phone number."
                    else -> onSave(
                        Contact(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            relation = relation.trim(),
                            phone = phone.trim(),
                        )
                    )
                }
            }) { Text("Save contact", color = C.Teal) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

import socket
import json
import threading
import tkinter as tk
from tkinter import messagebox, ttk
from datetime import datetime

class SunmiServerGUI:
    def __init__(self, root):
        self.root = root
        self.root.title("Sunmi Remote Print Server")
        self.root.geometry("500x750")

        self.client_socket = None
        self.server_running = True

        # --- UI Elements ---

        # Connection Status
        self.status_label = tk.Label(root, text="Status: Waiting for connection...", fg="orange", font=("Arial", 10, "bold"))
        self.status_label.pack(pady=10)

        self.ip_label = tk.Label(root, text=f"Local IP: {self.get_local_ip()}", font=("Arial", 9))
        self.ip_label.pack()

        # Format Selection
        tk.Label(root, text="Print Format:", font=("Arial", 10, "bold")).pack(anchor="w", padx=20, pady=(10,0))
        self.format_var = tk.StringVar(value="Plain")
        self.format_combo = ttk.Combobox(root, textvariable=self.format_var, state="readonly")
        self.format_combo['values'] = ("Plain", "Centered", "Boxed", "Header + Body", "Banner", "List", "Barcode", "QR Code", "Image", "B.A.N.U.S.U.G.E Alert")
        self.format_combo.pack(fill="x", padx=20, pady=5)
        self.format_combo.bind("<<ComboboxSelected>>", self.update_ui_state)

        # Title Input
        self.title_label = tk.Label(root, text="Title:")
        self.title_label.pack(anchor="w", padx=20)
        self.title_entry = tk.Entry(root, width=50)
        self.title_entry.pack(pady=5)
        self.title_entry.insert(0, "Remote Print")

        # Title Size
        self.tsize_label = tk.Label(root, text="Title Size (px):")
        self.tsize_label.pack(anchor="w", padx=20)
        self.title_size_entry = tk.Entry(root, width=10)
        self.title_size_entry.pack(pady=5, anchor="w", padx=20)
        self.title_size_entry.insert(0, "40")

        # Center Title Checkbox
        self.center_title_var = tk.BooleanVar(value=True)
        self.center_title_cb = tk.Checkbutton(root, text="Center Title", variable=self.center_title_var)
        self.center_title_cb.pack(anchor="w", padx=20)

        # Content Input
        self.content_label = tk.Label(root, text="Content / Data:")
        self.content_label.pack(anchor="w", padx=20, pady=(10, 0))
        self.content_text = tk.Text(root, width=50, height=5)
        self.content_text.pack(pady=5)
        self.content_text.insert("1.0", "Hello from the GUI server!")

        # Content Size
        self.csize_label = tk.Label(root, text="Content Size (px):")
        self.csize_label.pack(anchor="w", padx=20)
        self.content_size_entry = tk.Entry(root, width=10)
        self.content_size_entry.pack(pady=5, anchor="w", padx=20)
        self.content_size_entry.insert(0, "28")

        # Lines After
        tk.Label(root, text="Lines After Print:").pack(anchor="w", padx=20)
        self.lines_after_entry = tk.Entry(root, width=10)
        self.lines_after_entry.pack(pady=5, anchor="w", padx=20)
        self.lines_after_entry.insert(0, "3")

        # Alignment (0=Left, 1=Center)
        self.align_label = tk.Label(root, text="Content Alignment:")
        self.align_label.pack(anchor="w", padx=20)
        self.alignment_frame = tk.Frame(root)
        self.alignment_frame.pack(anchor="w", padx=20)
        self.alignment_var = tk.IntVar(value=0)
        tk.Radiobutton(self.alignment_frame, text="Left", variable=self.alignment_var, value=0).pack(side="left")
        tk.Radiobutton(self.alignment_frame, text="Center", variable=self.alignment_var, value=1).pack(side="left")

        # Send Button
        self.send_button = tk.Button(root, text="PRINT NOW", command=self.send_print_job,
                                     bg="#4CAF50", fg="white", font=("Arial", 12, "bold"),
                                     state="disabled", width=25)
        self.send_button.pack(side="bottom", pady=20)

        # Start server thread
        threading.Thread(target=self.start_socket_server, daemon=True).start()

    def update_ui_state(self, event=None):
        fmt = self.format_var.get()
        is_alert = (fmt == "B.A.N.U.S.U.G.E Alert")
        is_barcode = (fmt == "Barcode")
        is_qr = (fmt == "QR Code")
        is_image = (fmt == "Image")
        is_list = (fmt == "List")
        is_banner = (fmt == "Banner")

        # Reset states
        self.title_entry.config(state="normal")
        self.title_size_entry.config(state="normal")
        self.center_title_cb.config(state="normal")
        self.content_size_entry.config(state="normal")
        self.content_text.config(state="normal")

        if is_alert:
            self.title_entry.config(state="disabled")
            self.title_size_entry.config(state="disabled")
            self.center_title_cb.config(state="disabled")
            self.content_size_entry.config(state="disabled")
            curr = self.content_text.get("1.0", tk.END).strip()
            if curr == "Hello from the GUI server!":
                self.content_text.delete("1.0", tk.END)
                self.content_text.insert("1.0", "6666")
        elif is_barcode or is_qr:
            self.title_size_entry.config(state="disabled")
            self.content_size_entry.config(state="disabled")
            self.content_label.config(text="Data:")
        elif is_image:
            self.title_entry.config(state="disabled")
            self.title_size_entry.config(state="disabled")
            self.center_title_cb.config(state="disabled")
            self.content_size_entry.config(state="disabled")
            self.content_label.config(text="Image URL or Base64:")
        else:
            self.content_label.config(text="Content / Data:")
            curr = self.content_text.get("1.0", tk.END).strip()
            if curr == "6666":
                self.content_text.delete("1.0", tk.END)
                self.content_text.insert("1.0", "Hello from the GUI server!")

    def get_local_ip(self):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except:
            return "127.0.0.1"

    def start_socket_server(self):
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            server.bind(('0.0.0.0', 8080))
            server.listen(1)
        except Exception as e:
            print(f"Server error: {e}")
            return

        while self.server_running:
            try:
                conn, addr = server.accept()
                self.client_socket = conn
                self.root.after(0, self.on_client_connected, addr)

                with conn:
                    while self.server_running:
                        data = conn.recv(1024)
                        if not data: break
            except:
                pass

            self.client_socket = None
            self.root.after(0, self.on_client_disconnected)

    def on_client_connected(self, addr):
        self.status_label.config(text=f"Status: Connected to {addr[0]}", fg="green")
        self.send_button.config(state="normal")

    def on_client_disconnected(self):
        self.status_label.config(text="Status: Device disconnected", fg="red")
        self.send_button.config(state="disabled")

    def send_print_job(self):
        if not self.client_socket:
            messagebox.showerror("Error", "No device connected!")
            return

        try:
            fmt = self.format_var.get()
            # Map GUI strings to type keys for Android
            type_map = {
                "Plain": "plain",
                "Centered": "centered",
                "Boxed": "boxed",
                "Header + Body": "header_body",
                "Banner": "banner",
                "List": "list",
                "Barcode": "barcode",
                "QR Code": "qr",
                "Image": "image",
                "B.A.N.U.S.U.G.E Alert": "alert"
            }

            job = {
                "type": type_map.get(fmt, "plain"),
                "content": self.content_text.get("1.0", tk.END).strip(),
                "linesAfter": int(self.lines_after_entry.get() or 3),
                "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "title": self.title_entry.get(),
                "titleSize": int(self.title_size_entry.get() or 40),
                "contentSize": int(self.content_size_entry.get() or 28),
                "centerTitle": self.center_title_var.get(),
                "alignment": self.alignment_var.get()
            }

            message = json.dumps(job) + "\n"
            self.client_socket.sendall(message.encode('utf-8'))
        except Exception as e:
            messagebox.showerror("Error", f"Failed to send: {e}")

if __name__ == "__main__":
    root = tk.Tk()
    app = SunmiServerGUI(root)
    root.mainloop()

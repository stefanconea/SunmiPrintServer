import socket
import json
import threading
import tkinter as tk
from tkinter import messagebox, ttk
from datetime import datetime
import urllib.request
import urllib.parse
import io

class SunmiServerGUI:
    def __init__(self, root):
        self.root = root
        self.root.title("Sunmi Remote Print Server")
        self.root.geometry("800x850")

        self.client_socket = None
        self.server_running = True
        self.image_refs = {} # Store PhotoImage refs to prevent GC
        self.preview_counter = 0

        # --- UI Elements ---

        # Main horizontal split
        self.main_container = tk.Frame(root)
        self.main_container.pack(fill="both", expand=True, padx=10, pady=10)

        # Left Panel: Controls
        self.left_panel = tk.Frame(self.main_container)
        self.left_panel.pack(side="left", fill="y", padx=(0, 20))

        # Connection Status
        self.status_label = tk.Label(self.left_panel, text="Status: Waiting for connection...", fg="orange", font=("Arial", 10, "bold"))
        self.status_label.pack(pady=10)

        self.ip_label = tk.Label(self.left_panel, text=f"Local IP: {self.get_local_ip()}", font=("Arial", 9))
        self.ip_label.pack()

        # Format Selection
        tk.Label(self.left_panel, text="Print Format:", font=("Arial", 10, "bold")).pack(anchor="w", pady=(10,0))
        self.format_var = tk.StringVar(value="Plain")
        self.format_combo = ttk.Combobox(self.left_panel, textvariable=self.format_var, state="readonly")
        self.format_combo['values'] = ("Plain", "Centered", "Boxed", "Header + Body", "Banner", "List", "Barcode", "QR Code", "Image", "B.A.N.U.S.U.G.E Alert")
        self.format_combo.pack(fill="x", pady=5)
        self.format_combo.bind("<<ComboboxSelected>>", self.on_change)

        # Title Input
        self.title_label = tk.Label(self.left_panel, text="Title:")
        self.title_label.pack(anchor="w")
        self.title_entry = tk.Entry(self.left_panel, width=40)
        self.title_entry.pack(pady=5)
        self.title_entry.insert(0, "Remote Print")
        self.title_entry.bind("<KeyRelease>", self.on_change)

        # Title Size
        tk.Label(self.left_panel, text="Title Size (px):").pack(anchor="w")
        self.title_size_entry = tk.Entry(self.left_panel, width=10)
        self.title_size_entry.pack(pady=5, anchor="w")
        self.title_size_entry.insert(0, "40")
        self.title_size_entry.bind("<KeyRelease>", self.on_change)

        # Center Title Checkbox
        self.center_title_var = tk.BooleanVar(value=True)
        self.center_title_cb = tk.Checkbutton(self.left_panel, text="Center Title", variable=self.center_title_var, command=self.on_change)
        self.center_title_cb.pack(anchor="w")

        # Bold Title Checkbox
        self.bold_title_var = tk.BooleanVar(value=False)
        self.bold_title_cb = tk.Checkbutton(self.left_panel, text="Bold Title", variable=self.bold_title_var, command=self.on_change)
        self.bold_title_cb.pack(anchor="w")

        # Content Input
        self.content_label = tk.Label(self.left_panel, text="Content / Data:")
        self.content_label.pack(anchor="w", pady=(10, 0))
        self.content_text = tk.Text(self.left_panel, width=40, height=8)
        self.content_text.pack(pady=5)
        self.content_text.insert("1.0", "Hello from the GUI server!")
        self.content_text.bind("<KeyRelease>", self.on_change)

        # Content Size
        tk.Label(self.left_panel, text="Content Size (px):").pack(anchor="w")
        self.content_size_entry = tk.Entry(self.left_panel, width=10)
        self.content_size_entry.pack(pady=5, anchor="w")
        self.content_size_entry.insert(0, "28")
        self.content_size_entry.bind("<KeyRelease>", self.on_change)

        # Bold Content Checkbox
        self.bold_content_var = tk.BooleanVar(value=False)
        self.bold_content_cb = tk.Checkbutton(self.left_panel, text="Bold Content", variable=self.bold_content_var, command=self.on_change)
        self.bold_content_cb.pack(anchor="w")

        # Lines After
        tk.Label(self.left_panel, text="Lines After Print:").pack(anchor="w")
        self.lines_after_entry = tk.Entry(self.left_panel, width=10)
        self.lines_after_entry.pack(pady=5, anchor="w")
        self.lines_after_entry.insert(0, "3")

        # Alignment (0=Left, 1=Center)
        tk.Label(self.left_panel, text="Content Alignment:").pack(anchor="w")
        self.alignment_frame = tk.Frame(self.left_panel)
        self.alignment_frame.pack(anchor="w")
        self.alignment_var = tk.IntVar(value=0)
        tk.Radiobutton(self.alignment_frame, text="Left", variable=self.alignment_var, value=0, command=self.on_change).pack(side="left")
        tk.Radiobutton(self.alignment_frame, text="Center", variable=self.alignment_var, value=1, command=self.on_change).pack(side="left")

        # Send Button
        self.send_button = tk.Button(self.left_panel, text="PRINT NOW", command=self.send_print_job,
                                     bg="#4CAF50", fg="white", font=("Arial", 12, "bold"),
                                     state="disabled", width=25)
        self.send_button.pack(side="bottom", pady=20)

        # Right Panel: Receipt Preview
        self.right_panel = tk.Frame(self.main_container, bg="#CCCCCC")
        self.right_panel.pack(side="right", fill="both", expand=True)

        tk.Label(self.right_panel, text="Live Preview (384px)", font=("Arial", 10, "bold"), bg="#CCCCCC").pack(pady=5)

        self.preview_container = tk.Frame(self.right_panel, bg="white", width=384)
        self.preview_container.pack(fill="y", expand=True, padx=2, pady=2)
        self.preview_container.pack_propagate(False)

        self.preview_text = tk.Text(self.preview_container, width=48, bg="white", borderwidth=0, highlightthickness=0, font=("Courier", 10), wrap="word")
        self.preview_text.pack(fill="both", expand=True, padx=10, pady=10)
        self.preview_text.tag_configure("center", justify="center")
        self.preview_text.tag_configure("bold", font=("Courier", 10, "bold"))
        self.preview_text.tag_configure("big", font=("Courier", 14, "bold"))
        self.preview_text.tag_configure("banner", font=("Courier", 20, "bold"))

        # Start server thread
        threading.Thread(target=self.start_socket_server, daemon=True).start()
        self.update_ui_state()
        self.update_preview()

    def on_change(self, event=None):
        self.update_ui_state()
        self.update_preview()

    def update_preview(self):
        fmt = self.format_var.get()
        title = self.title_entry.get()
        content = self.content_text.get("1.0", tk.END).strip()
        center_title = self.center_title_var.get()
        bold_title = self.bold_title_var.get()
        bold_content = self.bold_content_var.get()
        alignment = self.alignment_var.get()

        my_id = self.preview_counter + 1
        self.preview_counter = my_id

        self.preview_text.config(state="normal")
        self.preview_text.delete("1.0", tk.END)

        if not title and not content and fmt != "B.A.N.U.S.U.G.E Alert":
            self.preview_text.config(state="disabled")
            return

        if fmt == "B.A.N.U.S.U.G.E Alert":
            self.preview_text.insert(tk.END, "ALERT\n", ("center", "big"))
            self.preview_text.insert(tk.END, "WARNING\n\n", "center")
            self.preview_text.insert(tk.END, "- - - - - - - - - - - - - - -\n\n", "center")
            self.preview_text.insert(tk.END, f"{content or '6666'}\n\n", ("center", "big"))
            self.preview_text.insert(tk.END, "- - - - - - - - - - - - - - -\n\n", "center")
            self.preview_text.insert(tk.END, "* * * * * * *\n", "center")
            self.preview_text.insert(tk.END, "unknown\n", "center")
            self.preview_text.insert(tk.END, f"sent: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n", "center")
            self.preview_text.insert(tk.END, f"recv: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n", "center")
            self.preview_text.insert(tk.END, "* * * * * * *\n\n", "center")
            self.preview_text.insert(tk.END, "Thank you for using B.A.N.U.S.U.G.E\n", ("center", "bold"))
        elif fmt in ["QR Code", "Barcode", "Image"]:
            self.preview_text.insert(tk.END, f"\n[ Rendering {fmt}... ]\n", "center")
            self.async_fetch_preview(fmt, content, my_id)
        else:
            tags = ["center"] if (center_title or fmt == "Centered") else []
            if title:
                self.preview_text.insert(tk.END, title + "\n", tuple(tags + (["bold"] if (fmt == "Header + Body" or bold_title) else [])))

            content_tags = ["center"] if (alignment == 1 or fmt == "Centered") else []
            if fmt == "Banner": content_tags.append("banner")
            elif bold_content: content_tags.append("bold")

            if content:
                if fmt == "List":
                    for line in content.split("\n"):
                        self.preview_text.insert(tk.END, f"• {line}\n", tuple(content_tags))
                else:
                    self.preview_text.insert(tk.END, content, tuple(content_tags))

        if fmt == "Boxed":
            full_text = self.preview_text.get("1.0", tk.END)
            self.preview_text.delete("1.0", tk.END)
            border = "+" + "-"*30 + "+\n"
            self.preview_text.insert(tk.END, border)
            for line in full_text.split("\n"):
                if line.strip():
                    self.preview_text.insert(tk.END, f"| {line[:26]:<28} |\n")
            self.preview_text.insert(tk.END, border)

        self.preview_text.config(state="disabled")

    def async_fetch_preview(self, fmt, data, req_id):
        if not data: return

        def task():
            try:
                url = ""
                if fmt == "QR Code":
                    url = f"https://api.qrserver.com/v1/create-qr-code/?size=150x150&data={urllib.parse.quote(data)}"
                elif fmt == "Barcode":
                    url = f"https://bwipjs-api.metafloor.com/?bcid=code128&text={urllib.parse.quote(data)}&scale=1"
                elif fmt == "Image":
                    if not data.startsWith("http"): return
                    url = data

                if not url: return

                req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
                with urllib.request.urlopen(req, timeout=5) as response:
                    raw_data = response.read()
                    img = tk.PhotoImage(data=raw_data)
                    # Use root.after to update UI safely
                    self.root.after(0, lambda: self.update_image_in_preview(img, req_id))
            except Exception as e:
                print(f"Preview fetch error: {e}")

        threading.Thread(target=task, daemon=True).start()

    def update_image_in_preview(self, img, req_id):
        if req_id != self.preview_counter: return # stale request

        self.preview_text.config(state="normal")
        self.preview_text.delete("1.0", tk.END)
        self.preview_text.image_create(tk.END, image=img)
        self.preview_text.insert(tk.END, "\n")
        self.image_refs['preview'] = img # Keep ref
        self.preview_text.config(state="disabled")

    def update_ui_state(self, event=None):
        fmt = self.format_var.get()
        is_alert = (fmt == "B.A.N.U.S.U.G.E Alert")
        is_barcode = (fmt == "Barcode")
        is_qr = (fmt == "QR Code")
        is_image = (fmt == "Image")

        # Reset states
        self.title_entry.config(state="normal")
        self.content_text.config(state="normal")

        if is_alert:
            self.title_entry.config(state="disabled")
            self.content_label.config(text="Alert Code:")
        elif is_barcode or is_qr:
            self.content_label.config(text="Data for Code:")
        elif is_image:
            self.title_entry.config(state="disabled")
            self.content_label.config(text="Image URL (Direct):")
        else:
            self.content_label.config(text="Content / Data:")

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
            print(f"Server socket error: {e}")
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
            type_map = {
                "Plain": "plain", "Centered": "centered", "Boxed": "boxed",
                "Header + Body": "header_body", "Banner": "banner", "List": "list",
                "Barcode": "barcode", "QR Code": "qr", "Image": "image",
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
                "boldTitle": self.bold_title_var.get(),
                "boldContent": self.bold_content_var.get(),
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

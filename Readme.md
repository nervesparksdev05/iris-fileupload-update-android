<h2>
  <a href="https://play.google.com/store/apps/details?id=com.nervesparks.irisGPT&hl=en_IN" style="color: white;">
    Iris
  </a>
</h2>

## Overview

**Iris** is a fully offline Android assistant built on **llama.cpp**, designed for privacy-first, on-device AI chat and **offline RAG (Retrieval-Augmented Generation)**.

- **Runs completely offline** after installing and downloading/copying models
- **Private by design**: inference and retrieval happen on-device
- **Extensible**: download GGUF models from Hugging Face (optional; requires internet only for download)
- **Offline RAG**: upload documents → local indexing → answers grounded only in your files

> ⚠️ Important: Iris does not require the internet to run once models are available locally. Any internet usage is only for optional model downloads.

---

## Key Features

### Offline Chat (llama.cpp)
- On-device LLM chat via llama.cpp
- Adjustable parameters (threads, temperature, top_k, top_p, etc.)
- Default model selection
- Text-to-Speech (TTS) and Speech-to-Text (STT) support

### Offline RAG Assistant (Local-only)
- Upload documents from your device
- Text extraction and chunking happens locally
- Embeddings computed on-device (offline)
- Retrieval is local: answers are generated using only your uploaded documents
- Index and documents are stored in app-local storage

**No cloud. No external database. No remote calls.**

---

## Screenshots

<div style="display: flex; gap: 15px; justify-content: center; flex-wrap: wrap;">
  <div style="text-align: center; width: 200px;">
    <img src="./images/main_screen.png" alt="Main Screen Screenshot" width="200">
    <p><strong>Main Screen</strong></p>
    <p>Main interface where users access core features.</p>
  </div>
  <div style="text-align: center; width: 200px;">
    <img src="./images/chat_screen.png" alt="Chat Screen Screenshot" width="200">
    <p><strong>Chat Screen</strong></p>
    <p>Offline chat experience powered by llama.cpp.</p>
  </div>
  <div style="text-align: center; width: 200px;">
    <img src="./images/settings_screen.png" alt="Settings Screen Screenshot" width="200">
    <p><strong>Settings Screen</strong></p>
    <p>Customize preferences, parameters, and defaults.</p>
  </div>
  <div style="text-align: center; width: 200px;">
    <img src="./images/models_screen.png" alt="Models Screen Screenshot" width="200">
    <p><strong>Models Screen</strong></p>
    <p>Manage local GGUF models, download or select defaults.</p>
  </div>
  <div style="text-align: center; width: 200px;">
    <img src="./images/parameters_screen.png" alt="Parameters Screen Screenshot" width="200">
    <p><strong>Parameters Screen</strong></p>
    <p>Tune performance and response behavior.</p>
  </div>
</div>

---

## Installation

### Google Play
- [Get Iris on Google Play](https://play.google.com/store/apps/details?id=com.nervesparks.irisGPT&hl=en_IN)

### GitHub Releases
- Go to **Releases**: https://github.com/nerve-sparks/iris_android/releases  
- Download the APK
- Install on your device

---

## Getting Started

1. **Install the app**
2. **Add an LLM GGUF model**
   - Download from the in-app models screen (requires internet once), or
   - Copy a GGUF model file into the app storage
3. **(Optional) Use Offline RAG**
   - Upload documents inside the app
   - Wait for indexing to complete
   - Ask questions — responses will be grounded only in those documents

---

## Optimizing Your Experience

Performance depends heavily on model size and your device compute.

- **Smaller models** → faster responses, lower memory usage, slightly lower quality
- **Larger models** → higher quality, more memory/compute, slower output

**Recommendation:** start with a small/medium GGUF and increase based on device capability.

---

## Disclaimer

- Iris may produce **incorrect or incomplete answers**, depending on model limitations and query complexity.
- For RAG: answers depend on the **quality and content** of uploaded documents.

---

## Build From Source

### Prerequisites
- Android Studio (latest stable)
- Android NDK as required by the project

### Clone Repositories

Clone Iris:
```bash
git clone https://github.com/nerve-sparks/iris_android.git

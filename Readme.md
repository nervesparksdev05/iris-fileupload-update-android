<<<<<<< HEAD
# Iris - Offline AI Chat for Android

<div align="center">

[![Get it on Google Play](https://img.shields.io/badge/Get%20it%20on-Google%20Play-blue?style=for-the-badge&logo=google-play)](https://play.google.com/store/apps/details?id=com.nervesparks.irisGPT&hl=en_IN)
[![GitHub release](https://img.shields.io/github/v/release/nerve-sparks/iris_android?style=for-the-badge)](https://github.com/nerve-sparks/iris_android/releases)
[![License](https://img.shields.io/badge/License-MIT-green.svg?style=for-the-badge)](LICENSE)

**Run powerful AI models completely offline on your Android device**

[Features](#features) • [Installation](#installation) • [Screenshots](#screenshots) • [Documentation](#documentation) • [Contributing](#contributing)

</div>

---

## 📖 Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Screenshots](#screenshots)
- [Installation](#installation)
    - [From Google Play](#from-google-play)
    - [From GitHub Releases](#from-github-releases)
    - [Initial Setup](#initial-setup)
- [Supported Models](#supported-models)
- [RAG & Document Processing](#rag--document-processing)
- [Performance Optimization](#performance-optimization)
- [Building from Source](#building-from-source)
- [Adding Custom Models](#adding-custom-models)
- [Configuration](#configuration)
- [Technical Details](#technical-details)
- [Contributing](#contributing)
- [Troubleshooting](#troubleshooting)
- [License](#license)
- [Contact](#contact)

---

## 🌟 Overview

**Iris** is a powerful, privacy-focused AI chat application for Android that runs **completely offline**. Built on [llama.cpp](https://github.com/ggerganov/llama.cpp), Iris brings state-of-the-art language models directly to your device without requiring an internet connection or sending your data to external servers.

### Why Iris?

- 🔒 **100% Private** - All processing happens on your device
- 📴 **Fully Offline** - Works in airplane mode after initial setup
- 🎯 **No API Costs** - Free to use with unlimited messages
- 🚀 **Multiple Models** - Support for various GGUF models
- 📄 **RAG Support** - Upload and query your own documents
- ⚡ **Optimized** - Native C++ backend for maximum performance

---

## ✨ Features

### Core Features

- **🔌 Offline Operation**
    - Run completely offline after downloading models
    - No internet required for inference
    - Works in airplane mode

- **🤖 Multiple AI Models**
    - Support for GGUF format models
    - Download directly from Hugging Face
    - Switch between models easily
    - Set default model for automatic loading

- **📚 Document Processing (RAG)**
    - Upload PDF, DOCX, TXT, and other documents
    - Semantic search across your documents
    - Ask questions about uploaded content
    - Document-only mode for accurate citations
    - Automatic embedding and indexing

- **🎨 User Experience**
    - Clean, modern Material Design 3 UI
    - Dark mode optimized
    - Smooth animations and transitions
    - Copy/share responses easily

### Advanced Features

- **⚙️ Customizable Inference**
    - Adjust `n_threads` for CPU utilization
    - Configure `top_k`, `top_p`, and `temperature`
    - Fine-tune for speed vs quality trade-offs

- **🎤 Voice Features**
    - Text-to-Speech (TTS) for AI responses
    - Speech-to-Text (STT) for voice input
    - Built-in Android TTS/STT integration

- **📊 Model Management**
    - Download and manage chat models
    - Separate embedding model management
    - View model sizes and status
    - Delete models to free up space

- **🔧 Developer-Friendly**
    - Open source and transparent
    - Easy to extend and modify
    - Active development and updates

---

## 📱 Screenshots

![WhatsApp Image 2026-01-13 at 5.29.56 PM.jpeg](../../../Pictures/nervesparks/WhatsApp%20Image%202026-01-13%20at%205.29.56%20PM.jpeg)
![WhatsApp Image 2026-01-13 at 5.29.57 PM (1).jpeg](../../../Pictures/nervesparks/WhatsApp%20Image%202026-01-13%20at%205.29.57%20PM%20%281%29.jpeg)
![WhatsApp Image 2026-01-13 at 5.29.57 PM.jpeg](../../../Pictures/nervesparks/WhatsApp%20Image%202026-01-13%20at%205.29.57%20PM.jpeg)
![WhatsApp Image 2026-01-13 at 5.29.58 PM (1).jpeg](../../../Pictures/nervesparks/WhatsApp%20Image%202026-01-13%20at%205.29.58%20PM%20%281%29.jpeg)
![WhatsApp Image 2026-01-13 at 5.29.58 PM.jpeg](../../../Pictures/nervesparks/WhatsApp%20Image%202026-01-13%20at%205.29.58%20PM.jpeg)

---

## 📥 Installation

### From Google Play

The easiest way to install Iris:

[![Get it on Google Play](https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png)](https://play.google.com/store/apps/details?id=com.nervesparks.irisGPT&hl=en_IN)

### From GitHub Releases

1. Navigate to the [Releases page](https://github.com/nerve-sparks/iris_android/releases)
2. Download the latest `.apk` file
3. Install the APK on your Android device
    - You may need to enable "Install from Unknown Sources" in your device settings

### Initial Setup

After installing the app:

1. **Open Iris** and navigate to the **Models** screen
2. **Download a Chat Model** (required)
    - Choose based on your device's capabilities
    - Recommended: Start with TinyLlama or Llama 3.2-1B for testing
3. **Download an Embedding Model** (optional but recommended)
    - Required for document processing features
    - Recommended: `bge-small-en-v1.5-q4_k_m.gguf` (~25MB)
4. **Wait for downloads to complete**
5. **Start chatting!** The default model will load automatically

> **Note:** Models are large files (ranging from ~600MB to 2GB+). Ensure you have sufficient storage space and a stable connection for initial downloads.

---

## 🤖 Supported Models

### Recommended Chat Models

| Model | Size | Quantization | Speed | Quality | Best For |
|-------|------|--------------|-------|---------|----------|
| **TinyLlama 1.1B** | ~600MB | Q4_K_M | ⚡⚡⚡ | ⭐⭐ | Quick responses, low-end devices |
| **Llama 3.2-1B** | ~800MB | Q6_K_L | ⚡⚡ | ⭐⭐⭐ | Balanced performance |
| **Llama 3.2-3B** | ~1.9GB | Q4_K_L | ⚡ | ⭐⭐⭐⭐ | Best quality for most devices |
| **StableLM 1.6B** | ~1GB | Q4_K_M | ⚡⚡ | ⭐⭐⭐ | Good balance |

### Embedding Models

| Model | Size | Best For |
|-------|------|----------|
| **bge-small-en-v1.5** | ~25MB | Document processing, fast retrieval |
| **mxbai-embed-large-v1** | ~670MB | High-quality embeddings, better accuracy |

### Model Selection Guide

**For Low-End Devices (2-4GB RAM):**
- TinyLlama 1.1B (Q4_K_M)
- bge-small-en-v1.5 embedding

**For Mid-Range Devices (4-6GB RAM):**
- Llama 3.2-1B (Q6_K_L) or StableLM 1.6B
- bge-small-en-v1.5 embedding

**For High-End Devices (6GB+ RAM):**
- Llama 3.2-3B (Q4_K_L)
- mxbai-embed-large-v1 embedding

---

## 📄 RAG & Document Processing

### What is RAG?

**Retrieval-Augmented Generation (RAG)** allows Iris to answer questions based on your uploaded documents. The system:

1. **Indexes** your documents using embeddings
2. **Retrieves** relevant sections when you ask questions
3. **Generates** answers using only the document content
4. **Cites** sources with document name and section numbers

### Supported Document Formats

- ✅ PDF (`.pdf`)
- ✅ Microsoft Word (`.docx`)
- ✅ Text Files (`.txt`)
- ✅ Markdown (`.md`)
- ✅ CSV (`.csv`)

### How to Use RAG

1. **Download an Embedding Model** (required)
    - Go to **Models → Embedding Models**
    - Download `bge-small-en-v1.5-q4_k_m.gguf`

2. **Upload Documents**
    - Go to **Documents** screen
    - Tap **Upload Document**
    - Select one or more files
    - Wait for indexing to complete

3. **Ask Questions**
    - Type your question in the chat
    - Iris will automatically search your documents
    - Answers include citations like `[Document Name §3]`

### Document Mode Behavior

When documents are uploaded, Iris operates in **Document-Only Mode**:

- ✅ Answers **ONLY** from uploaded documents
- ✅ Cites sources for all claims
- ✅ Says "not found" if information isn't in documents
- ❌ Does **NOT** use general knowledge or training data

This ensures:
- 📌 **Accuracy** - No hallucinations or made-up information
- 🎯 **Reliability** - Answers are always grounded in your documents
- 🔍 **Transparency** - Every claim is cited

### RAG Tips

**Best Practices:**
- Upload well-formatted documents (PDFs work best)
- Keep documents under 100MB each
- Use descriptive filenames
- Ask specific questions about document content

**Limitations:**
- Scanned PDFs (images) may not work well
- Very large documents may take time to index
- Model quality affects answer quality

---

## ⚡ Performance Optimization

### Device Requirements

**Minimum:**
- Android 8.0 (API 26) or higher
- 2GB RAM
- 2GB free storage

**Recommended:**
- Android 11.0 or higher
- 4GB+ RAM
- 5GB+ free storage

### Optimization Tips

#### For Faster Responses:

1. **Use Smaller Models**
    - TinyLlama 1.1B is 3-5x faster than 3B models
    - Lower quantizations (Q4) are faster than higher (Q6)

2. **Adjust Thread Count**
    - Settings → Parameters → `n_threads`
    - Use 4-6 threads for most devices
    - Higher = faster but more battery usage

3. **Lower Quality Settings**
    - Reduce `top_k` to 20-30
    - Lower `temperature` to 0.5-0.7

#### For Better Quality:

1. **Use Larger Models**
    - Llama 3.2-3B provides significantly better responses
    - Higher quantizations (Q6) have better accuracy

2. **Increase Context**
    - Keep more conversation history
    - Use higher `top_p` (0.9-0.95)

3. **Optimize Temperature**
    - Higher temperature (0.7-0.9) = more creative
    - Lower temperature (0.3-0.5) = more focused/factual

### Battery Life

**Recommendations:**
- Use smaller models for extended use
- Close the app when not in use
- Reduce thread count if overheating occurs
- Enable battery optimization for the app

---

## 🔨 Building from Source

### Prerequisites

- **Android Studio** (latest version recommended)
- **Git**
- **Android SDK** (API 26+)
- **NDK** (for native C++ compilation)

### Build Steps

1. **Clone the repository:**
=======
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
>>>>>>> d55e555f6092839146741500717606069d8ea533

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
<<<<<<< HEAD
cd iris_android
```

2. **Clone llama.cpp dependency:**

```bash
cd ..
git clone https://github.com/ggerganov/llama.cpp
cd llama.cpp
git checkout 1f922254f0c984a8fb9fbaa0c390d7ffae49aedb
cd ../iris_android
```

3. **Open in Android Studio:**
    - File → Open → Select `iris_android` folder
    - Wait for Gradle sync to complete

4. **Connect your device:**

   **Option A: USB Debugging**
    - Enable Developer Options on your device
    - Enable USB Debugging
    - Connect via USB
    - Authorize the computer

   **Option B: Wireless Debugging (Android 11+)**
    - Enable Developer Options
    - Enable Wireless Debugging
    - In Android Studio: Run → Pair Devices Using Wi-Fi
    - Scan the QR code shown on your device
    - Both devices must be on the same network

5. **Build and Run:**
    - Select your device from the dropdown
    - Click the **Run** button (▶️)
    - Wait for compilation and installation

6. **Download Models:**
    - Open the app
    - Navigate to **Models**
    - Download at least one chat model
    - (Optional) Download an embedding model

### Project Structure

```
iris_android/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/nervesparks/iris/
│   │   │   │   ├── data/                    # Data layer
│   │   │   │   │   └── UserPreferencesRepository.kt
│   │   │   │   ├── docs/                    # Document handling
│   │   │   │   │   ├── DocumentTextExtractor
│   │   │   │   │   └── DocumentUriPermission
│   │   │   │   ├── irisapp/                 # App initialization
│   │   │   │   │   ├── IrisApp
│   │   │   │   │   └── ServiceLocator
│   │   │   │   ├── rag/                     # RAG system
│   │   │   │   │   ├── embed/               # Embedding generation
│   │   │   │   │   │   ├── Embedder
│   │   │   │   │   │   └── LlamaCppEmbedder
│   │   │   │   │   ├── ingest/              # Document processing
│   │   │   │   │   │   ├── Chunker
│   │   │   │   │   │   └── TextNormalize
│   │   │   │   │   ├── retrieval/           # Search & retrieval
│   │   │   │   │   │   └── VectorSearch
│   │   │   │   │   ├── storage/             # Data persistence
│   │   │   │   │   │   └── LocalRagStore.kt
│   │   │   │   │   ├── util/                # Utilities
│   │   │   │   │   │   └── FloatPacking
│   │   │   │   │   ├── worker/              # Background tasks
│   │   │   │   │   │   └── IndexDocumentWorker
│   │   │   │   │   ├── RagModels.kt
│   │   │   │   │   └── RagRepository
│   │   │   │   ├── ui/                      # UI components
│   │   │   │   │   ├── components/          # Reusable components
│   │   │   │   │   ├── screens/             # App screens
│   │   │   │   │   └── theme/               # Material theming
│   │   │   │   ├── MainActivity.kt
│   │   │   │   └── MainViewModel.kt
│   │   │   ├── cpp/                         # Native C++ code
│   │   │   │   ├── llama-android.cpp        # JNI bridge to llama.cpp
│   │   │   │   └── CMakeLists.txt           # C++ build config
│   │   │   └── res/                         # Android resources
│   │   │       ├── drawable/                # Images & icons
│   │   │       ├── layout/                  # XML layouts (if any)
│   │   │       ├── values/                  # Strings, colors, themes
│   │   │       └── xml/                     # Preferences & config
│   ├── build.gradle                         # App-level build config
│   └── CMakeLists.txt                       # Root C++ build config
├── gradle/                                  # Gradle wrapper
├── llama.cpp/                               # llama.cpp submodule
├── build.gradle                             # Project-level build config
├── settings.gradle                          # Project settings
├── README.md                                # This file
└── LICENSE                                  # License file
```

---

## 🎯 Adding Custom Models

### Adding a Chat Model (GGUF)

1. **Find the model on Hugging Face**
    - Browse [Hugging Face](https://huggingface.co/models)
    - Look for GGUF format models

2. **Get the direct download URL:**
    - ✅ Correct format: `https://huggingface.co/<user>/<repo>/resolve/main/<file>.gguf?download=true`
    - ❌ Wrong format: `https://huggingface.co/<user>/<repo>/blob/main/<file>.gguf`

3. **Add to `MainViewModel.kt`:**

```kotlin
var allModels by mutableStateOf(
    listOf(
        // ... existing models ...
        mapOf(
            "name" to "your-model-name.gguf",
            "source" to "https://huggingface.co/user/repo/resolve/main/your-model-name.gguf?download=true",
            "destination" to "your-model-name.gguf"
        )
    )
)
```

### Adding an Embedding Model

Add to the `embeddingModels` list:

```kotlin
var embeddingModels by mutableStateOf(
    listOf(
        // ... existing models ...
        mapOf(
            "name" to "your-embedding-model.gguf",
            "source" to "https://huggingface.co/user/repo/resolve/main/your-embedding-model.gguf?download=true",
            "destination" to "your-embedding-model.gguf",
            "size" to "~50MB",
            "description" to "Description of your embedding model"
        )
    )
)
```

### Testing Custom Models

After adding:
1. Rebuild the app
2. The model appears in **Suggested Models**
3. Download and test thoroughly
4. Share your findings with the community!

---

## ⚙️ Configuration

### Inference Parameters

Configure in **Settings → Parameters**:

| Parameter | Range | Default | Description |
|-----------|-------|---------|-------------|
| **Temperature** | 0.0 - 2.0 | 0.7 | Controls randomness (lower = more focused, higher = more creative) |
| **Top P** | 0.0 - 1.0 | 0.9 | Nucleus sampling threshold |
| **Top K** | 1 - 100 | 40 | Limits vocabulary to top K tokens |
| **Threads** | 1 - 8 | 4 | CPU threads for inference (more = faster but more battery) |

### Recommended Configurations

**For Factual Q&A / Documents:**
```
Temperature: 0.3
Top P: 0.9
Top K: 30
```

**For Creative Writing:**
```
Temperature: 0.8
Top P: 0.95
Top K: 50
```

**For Balanced Performance:**
```
Temperature: 0.7
Top P: 0.9
Top K: 40
```

---

## 🔧 Technical Details

### Architecture

- **Frontend:** Jetpack Compose (Material Design 3)
- **Backend:** llama.cpp (C++ native)
- **RAG System:** Custom implementation with vector search
- **Embedding:** BGE/MxBai models via llama.cpp
- **Document Processing:** Apache POI, PdfBox, iText
- **Storage:** Local file system with JSONL format

### Key Technologies

- **Kotlin** - Primary language
- **Jetpack Compose** - Modern UI toolkit
- **llama.cpp** - High-performance LLM inference
- **Coroutines** - Asynchronous operations
- **Flow** - Reactive data streams
- **WorkManager** - Background document indexing

### RAG Implementation

> **📚 For complete RAG system documentation, see [RAG_ARCHITECTURE.md](RAG_ARCHITECTURE.md)**

**Architecture Overview:**

The RAG system is organized into modular components for maintainability and scalability:

```
com.nervesparks.iris/rag/
├── embed/                      # Embedding generation
│   ├── Embedder               # Embedding interface
│   └── LlamaCppEmbedder       # llama.cpp implementation
├── ingest/                     # Document processing
│   ├── Chunker                # Text chunking logic
│   └── TextNormalize          # Text cleanup & normalization
├── retrieval/                  # Search & retrieval
│   └── VectorSearch           # Similarity search implementation
├── storage/                    # Data persistence
│   └── LocalRagStore.kt       # File-based storage manager
├── util/                       # Utilities
│   └── FloatPacking           # Float32 binary serialization
├── worker/                     # Background processing
│   └── IndexDocumentWorker    # Async document indexing
├── RagModels.kt               # Data models & types
└── RagRepository              # Main RAG coordinator
```

**Component Details:**

1. **Document Ingestion** (`docs/`)
    - `DocumentTextExtractor` - Extracts text from PDF, DOCX, TXT, CSV
    - `DocumentUriPermission` - Manages Android URI permissions
    - Supports multiple document formats with format-specific extractors

2. **Text Processing** (`rag/ingest/`)
    - `TextNormalize` - Cleans and normalizes extracted text
    - `Chunker` - Splits documents into semantic chunks (900 chars with 250 char overlap)
    - Handles deduplication and quality filtering

3. **Embedding** (`rag/embed/`)
    - `Embedder` - Abstract interface for embedding models
    - `LlamaCppEmbedder` - Concrete implementation using llama.cpp
    - Converts text chunks to dense vector representations

4. **Vector Storage** (`rag/storage/`)
    - `LocalRagStore` - Manages document metadata and embeddings
    - File-based storage with atomic writes
    - Efficient binary format for embeddings

5. **Retrieval** (`rag/retrieval/`)
    - `VectorSearch` - Implements dot product similarity search
    - Fast in-memory search across document chunks
    - Configurable top-k and score threshold

6. **Background Processing** (`rag/worker/`)
    - `IndexDocumentWorker` - WorkManager-based async indexing
    - Handles long-running document processing
    - Progress tracking and error handling

7. **Coordination** (`RagRepository`)
    - Main entry point for RAG operations
    - Manages document lifecycle (add, remove, query)
    - Coordinates between all components

**Storage Format:**
```
/data/data/com.nervesparks.iris/files/
├── rag/
│   └── docs/
│       └── {docId}/
│           ├── meta.json           # Document metadata (name, status, etc.)
│           ├── chunks.jsonl        # Text chunks (one per line)
│           └── embeddings.bin      # Binary float32 vectors (LE)
└── user_docs/                      # Temporary upload storage
    └── {timestamp}_{uuid}_{filename}
```

**Data Flow:**

1. **Indexing Pipeline:**
   ```
   User Upload → DocumentUriPermission → Copy to user_docs/
   → IndexDocumentWorker → DocumentTextExtractor → TextNormalize
   → Chunker → LlamaCppEmbedder → LocalRagStore
   → Update Status to READY
   ```

2. **Retrieval Pipeline:**
   ```
   User Query → RagRepository.retrieve() → LlamaCppEmbedder (query)
   → LocalRagStore (load docs) → VectorSearch (similarity)
   → RagRepository.buildContextBlock() → Inject into LLM prompt
   ```

**Key Design Decisions:**

- **File-based storage** for simplicity and debugging
- **Binary embeddings** for space efficiency (float32 little-endian)
- **JSONL for chunks** for easy streaming and inspection
- **WorkManager** for reliable background processing
- **Atomic writes** to prevent corruption on crashes

**Component Interaction Diagram:**

```
┌─────────────────────────────────────────────────────────────────┐
│                         User Interface                          │
└────────────┬───────────────────────────────────┬────────────────┘
             │ Upload Document                   │ Ask Question
             ▼                                   ▼
    ┌────────────────────┐              ┌─────────────────┐
    │  RagRepository     │              │ MainViewModel   │
    │  .addDocuments()   │              │ .send()         │
    └────────┬───────────┘              └────────┬────────┘
             │                                   │
             ▼                                   ▼
    ┌─────────────────────┐            ┌──────────────────┐
    │ IndexDocumentWorker │            │ RagRepository    │
    │ (Background)        │            │ .retrieve()      │
    └────────┬────────────┘            └────────┬─────────┘
             │                                   │
             │ 1. Extract Text                   │ 1. Embed Query
             ├──────────────────►                │
             │ DocumentTextExtractor             ├──────────────────►
             │                                   │ LlamaCppEmbedder
             │ 2. Normalize                      │
             ├──────────────────►                │ 2. Load Docs
             │ TextNormalize                     │
             │                                   ├──────────────────►
             │ 3. Chunk                          │ LocalRagStore
             ├──────────────────►                │
             │ Chunker                           │ 3. Search
             │                                   │
             │ 4. Embed Chunks                   ├──────────────────►
             ├──────────────────►                │ VectorSearch
             │ LlamaCppEmbedder                  │
             │                                   │ 4. Build Context
             │ 5. Store                          │
             ├──────────────────►                └────────┬─────────┘
             │ LocalRagStore                              │
             │                                            ▼
             ▼                                   ┌─────────────────┐
    ┌─────────────────┐                         │ LLM Generation  │
    │ Document READY  │                         │ with Citations  │
    └─────────────────┘                         └─────────────────┘
```

### Performance Characteristics

**Model Loading:**
- TinyLlama 1.1B: ~2-4 seconds
- Llama 3.2-3B: ~5-10 seconds

**Inference Speed (tokens/sec):**
- TinyLlama on mid-range device: 10-15 t/s
- Llama 3.2-3B on mid-range device: 3-6 t/s

**Document Indexing:**
- Speed: ~5-10 pages/second
- Depends on embedding model and device

---

## 🤝 Contributing

We welcome contributions from the community! Here's how you can help:

### Ways to Contribute

1. **🐛 Report Bugs**
    - Use GitHub Issues
    - Include device info, Android version, and steps to reproduce
    - Attach logs if possible

2. **💡 Suggest Features**
    - Open a GitHub Discussion
    - Explain the use case and benefits
    - Share mockups if applicable

3. **📝 Improve Documentation**
    - Fix typos and unclear sections
    - Add examples and tutorials
    - Translate to other languages

4. **💻 Submit Code**
    - Follow the contribution workflow below
    - Ensure code quality and testing
    - Update documentation as needed

### Contribution Workflow

1. **Fork the repository**

```bash
git clone https://github.com/YOUR_USERNAME/iris_android.git
cd iris_android
```

2. **Create a feature branch**

```bash
git checkout -b feature/amazing-feature
```

3. **Make your changes**
    - Follow Kotlin coding conventions
    - Write clear commit messages
    - Add tests if applicable

4. **Test thoroughly**
    - Build and run on multiple devices
    - Test edge cases
    - Verify no regressions

5. **Commit your changes**

```bash
git commit -m "Add amazing feature that does X"
```

6. **Push to your fork**

```bash
git push origin feature/amazing-feature
```

7. **Open a Pull Request**
    - Describe your changes clearly
    - Reference related issues
    - Wait for review and address feedback

### Code Style Guidelines

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful variable and function names
- Add comments for complex logic
- Keep functions small and focused
- Write self-documenting code

### Commit Message Format

```
<type>: <subject>

<body>

<footer>
```

**Types:**
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes (formatting, etc.)
- `refactor`: Code refactoring
- `test`: Adding tests
- `chore`: Maintenance tasks

**Example:**
```
feat: Add support for EPUB document format

- Implement EPUB text extraction
- Add EPUB to supported formats list
- Update document picker UI

Closes #123
```

---

## ❓ Troubleshooting

### Common Issues

#### App Crashes on Model Load

**Symptoms:** App crashes when loading a model

**Solutions:**
- Ensure sufficient free RAM (close other apps)
- Try a smaller model
- Reduce thread count in settings
- Clear app cache and retry

#### Document Processing Fails

**Symptoms:** "Indexing failed" or "No text extracted"

**Solutions:**
- Ensure embedding model is downloaded
- Check document format is supported
- Try a different document
- Verify document isn't password-protected or corrupted

#### Slow Performance

**Symptoms:** Long wait times for responses

**Solutions:**
- Switch to a smaller model
- Reduce thread count (paradoxically can help on some devices)
- Close background apps
- Restart the device
- Check device isn't overheating

#### Model Download Fails

**Symptoms:** Download stops or shows error

**Solutions:**
- Check internet connection stability
- Ensure sufficient storage space
- Try downloading again
- Use Wi-Fi instead of mobile data
- Clear app cache

#### RAG Not Working

**Symptoms:** App doesn't use document context

**Solutions:**
- Verify embedding model is downloaded and shows "READY"
- Check document status shows "READY" not "INDEXING" or "FAILED"
- Wait for indexing to complete
- Try re-uploading the document
- Check logs for specific errors

### Getting Help

If you're still experiencing issues:

1. **Check GitHub Issues** - Your problem might already be solved
2. **Open a New Issue** - Include:
    - Device model and Android version
    - App version
    - Steps to reproduce
    - Logs (if available)
    - Screenshots
3. **Join Discussions** - Ask the community
4. **Contact Support** - Visit [nervesparks.com](https://www.nervesparks.com)

### Debug Logs

To collect logs for bug reports:

```bash
adb logcat | grep -i "iris\|llama"
```

Or use Android Studio's Logcat window.

---

## 🙏 Acknowledgments

Special thanks to:

- **[llama.cpp](https://github.com/ggerganov/llama.cpp)** - For the incredible inference engine
- **Hugging Face** - For hosting models and making AI accessible
- **Open Source Community** - For continuous inspiration and support
- **Our Users** - For feedback and support

---

## 📚 Documentation

### Additional Resources

- **[RAG_ARCHITECTURE.md](RAG_ARCHITECTURE.md)** - Comprehensive RAG system documentation
    - Component descriptions
    - Data flow diagrams
    - Performance characteristics
    - Debugging guide

- **[CONTRIBUTING.md](CONTRIBUTING.md)** - Contribution guidelines (if available)
- **[CHANGELOG.md](CHANGELOG.md)** - Version history (if available)

---

## 🗺️ Roadmap

Future plans for Iris:

- [ ] Support for more document formats (EPUB, RTF)
- [ ] Multi-modal support (images in chat)
- [ ] Cloud sync for conversations (optional)
- [ ] Custom system prompts
- [ ] Conversation export/import
- [ ] Plugin system for extensions
- [ ] Voice-first interaction mode
- [ ] Better multi-language support

---

<div align="center">

**Made with ❤️ by [Nerve Sparks](https://www.nervesparks.com)**

⭐ Star us on GitHub if you find Iris useful!

</div>
=======
>>>>>>> d55e555f6092839146741500717606069d8ea533

import sys, json
from docling.document_converter import DocumentConverter

def main():
    if len(sys.argv) < 2:
        print(json.dumps({}))
        return
    pdf_path = sys.argv[1]
    conv = DocumentConverter()
    result = conv.convert(pdf_path)
    print(json.dumps(result.document.to_dict()))

if __name__ == "__main__":
    main()

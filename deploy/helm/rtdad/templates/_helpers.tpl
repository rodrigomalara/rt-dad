{{- define "rtdad.rabbitmqEnv" -}}
- name: SPRING_RABBITMQ_USERNAME
  valueFrom:
    secretKeyRef:
      name: {{ .Values.rabbitmq.credentialsSecret }}
      key: username
- name: SPRING_RABBITMQ_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .Values.rabbitmq.credentialsSecret }}
      key: password
{{- end -}}
